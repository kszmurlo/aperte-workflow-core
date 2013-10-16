package pl.net.bluesoft.rnd.pt.ext.emailcapture;

import org.aperteworkflow.cmis.widget.CmisAtomSessionFacade;
import pl.net.bluesoft.rnd.processtool.ProcessToolContext;
import pl.net.bluesoft.rnd.processtool.bpm.ProcessToolBpmSession;
import pl.net.bluesoft.rnd.processtool.bpm.ProcessToolBpmSessionHelper;
import pl.net.bluesoft.rnd.processtool.model.BpmTask;
import pl.net.bluesoft.rnd.processtool.model.ProcessInstance;
import pl.net.bluesoft.rnd.processtool.model.config.ProcessStateAction;
import pl.net.bluesoft.rnd.processtool.model.processdata.ProcessInstanceSimpleAttribute;
import pl.net.bluesoft.rnd.pt.ext.emailcapture.model.EmailCheckerConfiguration;
import pl.net.bluesoft.rnd.pt.ext.emailcapture.model.EmailCheckerRuleConfiguration;

import javax.mail.*;
import javax.mail.search.FlagTerm;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import static pl.net.bluesoft.rnd.processtool.plugins.ProcessToolRegistry.Util.getRegistry;
import static pl.net.bluesoft.util.lang.FormatUtil.nvl;
import static pl.net.bluesoft.util.lang.StringUtil.hasText;

public class EmailChecker {

    private static final Logger logger = Logger.getLogger(EmailChecker.class.getName());

    private ProcessToolContext context;

    public EmailChecker(ProcessToolContext context) {
        this.context = context;
    }

    public void run() {
        List<EmailCheckerConfiguration> configs = context.getHibernateSession().createCriteria(EmailCheckerConfiguration.class).list();
        for (EmailCheckerConfiguration cfg : configs) {
            try {
                execute(cfg);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    public void execute(EmailCheckerConfiguration cfg) throws Exception {
        ProcessToolBpmSession toolBpmSession = getRegistry().getProcessToolSessionFactory().createSession(cfg.getAutomaticUser());

        ByteArrayInputStream bis = new ByteArrayInputStream(cfg.getMailSessionProperties().getBytes());
        final Properties cfgProperties = new Properties();
        cfgProperties.load(bis);


        final String protocol = cfgProperties.getProperty("mail.store.protocol");
        Session session = Session.getInstance(cfgProperties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfgProperties.getProperty("mail." + protocol + ".user"),
                        cfgProperties.getProperty("mail." + protocol + ".password"));
            }
        });

        Store store = session.getStore();
        store.connect();

        logger.info("Connected to mail service using " + cfg.getMailSessionProperties());
        String searchDirectory = cfgProperties.getProperty("search.directory");
        Folder folder = hasText(searchDirectory) ? store.getFolder(searchDirectory) : store.getFolder("inbox");
        folder.open(Folder.READ_WRITE);
        logger.info("Folder " + searchDirectory + " opened successfully");
        Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        logger.info("Found " + messages.length + " messages in " + folder.getFullName());
        List<Message> processed = new ArrayList();
        for (Message msg : messages) {
            try {
                processMessage(msg, cfg, toolBpmSession);
                processed.add(msg);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
            }

        }
        folder.setFlags(processed.toArray(new Message[processed.size()]), new Flags(Flags.Flag.SEEN), true);
        folder.close(false);
        store.close();
    }

    private void processMessage(Message msg, EmailCheckerConfiguration cfg, ProcessToolBpmSession toolBpmSession) throws MessagingException, IOException {
        String subject = msg.getSubject();
        StringBuilder recipientBuilder = new StringBuilder();
		if (msg.getHeader("To") != null) {
			for (String h : msg.getHeader("To")) {
				recipientBuilder.append(h).append(",");
			}
		}
		if (msg.getHeader("Cc") != null) {
			for (String h : msg.getHeader("Cc")) {
				recipientBuilder.append(h).append(",");
			}
		}
		String recipients = recipientBuilder.toString();
        StringBuilder senderBuilder = new StringBuilder();
		if (msg.getFrom() != null) {
			for (Address a : msg.getFrom()) {
				senderBuilder.append(a.toString()).append(",");
			}
		}
		String sender = senderBuilder.toString();

        String description = subject + ", from: " + recipients + ", sent by: " + sender;
        logger.fine("Processing message: " + description);

        for (EmailCheckerRuleConfiguration rule : cfg.getRules()) {
            logger.fine("Checking rule " + rule.getId() + " against message " + description);
            if (hasText(rule.getSubjectRegexp())) {
                if (subject == null || !subject.matches(rule.getSubjectRegexp())) {
                    continue;
                }
            }

            String preparedSubject = subject.toUpperCase();
            if (hasText(rule.getSubjectRemovables())) {
                for (String removable : rule.getSubjectRemovables().split("\\s")) {
                    preparedSubject = preparedSubject.replace(removable.toUpperCase(), "");
                }
            }
            preparedSubject = preparedSubject.replaceAll("[^A-Z0-9_\\-]*", "");
            logger.fine("Prepared subject: " + preparedSubject);

            ProcessInstance existingPi = null;
            if (rule.isLookupRunningProcesses()) {
                List<ProcessInstance> instancesByExternalKey = Collections.emptyList();
//				context.getProcessInstanceDAO().findProcessInstancesByKeyword(preparedSubject, rule.getProcessCode());
                for (ProcessInstance pi : instancesByExternalKey) {
					logger.fine("Found existing process for " + preparedSubject + ", ID: " + pi.getInternalId());
                    if (pi.isProcessRunning() && pi.getDefinition().getBpmDefinitionKey().equals(rule.getProcessCode())) {
                        logger.info("Found existing and RUNNING process for " + preparedSubject + ", ID: " + pi.getInternalId());
                        existingPi = pi;
                        break;
                    }
                }
            }
            if (existingPi == null) {
                if (hasText(rule.getRecipientRegexp())) {
                    if (recipients == null || recipients.isEmpty() || !recipients.matches(rule.getRecipientRegexp())) {
                        continue;
                    }
                }
                if (hasText(rule.getSenderRegexp())) {
                    if (sender == null || sender.isEmpty() || !sender.matches(rule.getSenderRegexp())) {
                        continue;
                    }
                }
            }
            logger.fine("Rule " + rule.getId() + " has matched message " + description + ", existing process: " + existingPi);

            if (existingPi == null && hasText(rule.getProcessIdSubjectLookupRegexp())) {
                Matcher m = java.util.regex.Pattern.compile(rule.getProcessIdSubjectLookupRegexp()).matcher(subject);
                if (m.matches()) {
                    String processId = m.group(1);
                    existingPi = nvl(
                            context.getProcessInstanceDAO().getProcessInstanceByExternalId(processId),
                            context.getProcessInstanceDAO().getProcessInstanceByInternalId(processId));
                    if (existingPi != null) {
                        logger.fine("Found existing process for " + processId + ", ID: " + existingPi.getInternalId());
                    }
                }
            }

            if (existingPi == null && rule.getStartNewProcesses()) {
                logger.fine("Starting new process for rule " + rule.getId() + " on matched message " + description +
                        ", process code: " + rule.getProcessCode());
                existingPi = ProcessToolBpmSessionHelper.startProcess(toolBpmSession, context, rule.getProcessCode(),
						null, "email").getProcessInstance();
                //save initial email data
                existingPi.setSimpleAttribute("email_from", sender);
                existingPi.setSimpleAttribute("email_subject", msg.getSubject());
                if (msg.getContent() instanceof Multipart) {
                    Multipart multipart = (Multipart) msg.getContent();
                    for (int i = 0; i < multipart.getCount(); ++i) {
                        BodyPart part = multipart.getBodyPart(i);
                        if (part.getContentType() == null || part.getContentType().startsWith("text/")) {
                            logger.info("Skipping multipart attachment #" + i);
                            continue;
                        }
                        existingPi.setSimpleAttribute("email_body", new String(toByteArray(part.getInputStream())));
                    }
                } else {
                    existingPi.setSimpleAttribute("email_body", new String(toByteArray(msg.getInputStream())));
                }
                context.getProcessInstanceDAO().saveProcessInstance(existingPi);
                logger.info("Started new process for rule " + rule.getId() + " on matched message " + description +
                        ", process code: " + rule.getProcessCode() + " new process id: " + existingPi.getInternalId());

            }

            if (existingPi != null && hasText(rule.getRunningProcessActionName())) {
                Collection<BpmTask> taskList = ProcessToolBpmSessionHelper.findProcessTasks(toolBpmSession, context, existingPi);
                for (BpmTask t : taskList) {
                    if (!hasText(rule.getProcessTaskName()) || rule.getProcessTaskName().equalsIgnoreCase(t.getTaskName())) {
                        Set<ProcessStateAction> actions = t.getCurrentProcessStateConfiguration().getActions();
                        for (ProcessStateAction a : actions) {
                            if (rule.getRunningProcessActionName().equals(a.getBpmName())) {
								ProcessToolBpmSessionHelper.performAction(toolBpmSession, context, a, t);
                                logger.info("Performed action " + rule.getId() + " on matched message " + description +
                                        ", process code: " + rule.getProcessCode() + " process id: " + existingPi.getInternalId());
                                break;
                            }
                        }
                    }
                }

            }
            if (existingPi != null && hasText(rule.getRepositoryAtomUrl())) 
            {
                logger.fine("Uploading CMIS documents, process ID: " + existingPi.getInternalId());
                
                CmisAtomSessionFacade sessionFacade = new CmisAtomSessionFacade();

                String folderId = null;
                for (ProcessInstanceSimpleAttribute at : existingPi.getProcessSimpleAttributes()) {
					if (at.getKey().equals(rule.getFolderAttributeName())) {
						folderId = at.getValue();
						break;
					}
                }
                org.apache.chemistry.opencmis.client.api.Folder mainFolder;
                if (folderId == null) {
                    mainFolder = sessionFacade.createFolderIfNecessary(nvl(rule.getNewFolderPrefix(), "") +
                            existingPi.getInternalId(), rule.getRootFolderPath());
                    if (hasText(rule.getSubFolder()))
                        mainFolder = sessionFacade.createFolderIfNecessary(rule.getSubFolder(), mainFolder.getPath());
                    folderId = mainFolder.getId();

                    existingPi.setSimpleAttribute("emailSender", sender);
                    existingPi.setSimpleAttribute("emailSubject", subject);
                    existingPi.setSimpleAttribute(nvl(rule.getFolderAttributeName(), "emailFolderId"), folderId);

                    context.getProcessInstanceDAO().saveProcessInstance(existingPi);
                } else {
                    mainFolder = sessionFacade.getFolderById(folderId);
                }

                if (msg.getContent() instanceof Multipart) {
                    Multipart multipart = (Multipart) msg.getContent();
                    for (int i = 0; i < multipart.getCount(); ++i) {
                        BodyPart part = multipart.getBodyPart(i);
                        if (rule.isOmitTextAttachments() &&
                                (part.getContentType() == null || part.getContentType().startsWith("text/"))) {
                            logger.info("Skipping multipart attachment #" + i);
                            continue;
                        }

                        sessionFacade.uploadDocument(hasText(part.getFileName()) ? part.getFileName() : "part_" + i,
                                mainFolder,
                                toByteArray(part.getInputStream()),
                                part.getContentType(), null);
                    }
                } else {
                    if (!rule.isOmitTextAttachments())
                        sessionFacade.uploadDocument("message",
                                mainFolder,
                                toByteArray(msg.getInputStream()),
                                msg.getContentType(), null);
                }

            }


        }
    }

    private byte[] toByteArray(InputStream inputStream) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = inputStream.read()) >= 0) {
                bos.write(c);
            }
            return bos.toByteArray();
        } finally {
            inputStream.close();
        }
    }

}
