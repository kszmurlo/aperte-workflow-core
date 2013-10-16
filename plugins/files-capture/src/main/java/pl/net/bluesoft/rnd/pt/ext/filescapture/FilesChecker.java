package pl.net.bluesoft.rnd.pt.ext.filescapture;

import org.apache.chemistry.opencmis.client.api.Folder;
import org.aperteworkflow.cmis.widget.CmisAtomSessionFacade;
import pl.net.bluesoft.rnd.processtool.ProcessToolContext;
import pl.net.bluesoft.rnd.processtool.bpm.ProcessToolBpmSession;
import pl.net.bluesoft.rnd.processtool.bpm.ProcessToolBpmSessionHelper;
import pl.net.bluesoft.rnd.processtool.model.BpmTask;
import pl.net.bluesoft.rnd.processtool.model.ProcessInstance;
import pl.net.bluesoft.rnd.processtool.model.config.ProcessStateAction;
import pl.net.bluesoft.rnd.processtool.model.processdata.ProcessInstanceSimpleAttribute;
import pl.net.bluesoft.rnd.pt.ext.filescapture.model.FilesCheckerConfiguration;
import pl.net.bluesoft.rnd.pt.ext.filescapture.model.FilesCheckerRuleConfiguration;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import static pl.net.bluesoft.rnd.processtool.plugins.ProcessToolRegistry.Util.getRegistry;
import static pl.net.bluesoft.util.lang.FormatUtil.nvl;
import static pl.net.bluesoft.util.lang.StringUtil.hasText;

/**
 * Created by Agata Taraszkiewicz
 */
public class FilesChecker {

    private static final Logger logger = Logger.getLogger(FilesChecker.class.getName());

    private ProcessToolContext context;

    public FilesChecker(ProcessToolContext context) {
        this.context = context;
    }

    public void run() {
        List<FilesCheckerConfiguration> configs = context.getHibernateSession().createCriteria(FilesCheckerConfiguration.class).list();
        for (FilesCheckerConfiguration cfg : configs) {
            try {
                execute(cfg);
            } catch (Exception e) {
                logger.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    private void execute(FilesCheckerConfiguration cfg) throws Exception {
        ProcessToolBpmSession toolBpmSession = getRegistry().getProcessToolSessionFactory().createSession(cfg.getAutomaticUser());

        File file = new File(cfg.getFilesProperties());

        if (file.isDirectory()) {
            String[] dirList = file.list();
            if (dirList != null) {
                processDirs(dirList, cfg, toolBpmSession);
            }
        }
    }

    private void processDirs(String[] dirList, FilesCheckerConfiguration cfg, ProcessToolBpmSession toolBpmSession) throws IOException {
        for (int i = 0; i < dirList.length; ++i) {
            File dir = new File(cfg.getFilesProperties()+ "/" + dirList[i]);
            if (!dir.isDirectory()) {
                dir.delete();
            } else {
                File finish = new File(dir.getAbsolutePath() + "/.finish");
                if (finish.exists()) {
                    processFiles(dir, cfg, toolBpmSession);
                }
            }
        }
    }

    private void processFiles(File dir, FilesCheckerConfiguration cfg, ProcessToolBpmSession toolBpmSession) throws IOException {

        for (FilesCheckerRuleConfiguration rule : cfg.getRules()) {
            ProcessInstance existingPi = null;
            if (hasText(rule.getProcessIdSubjectLookupRegexp())) {
                Matcher m = java.util.regex.Pattern.compile(rule.getProcessIdSubjectLookupRegexp()).matcher(dir.getName());
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
            if (existingPi != null && hasText(rule.getRunningProcessActionName())) {
                Collection<BpmTask> taskList = ProcessToolBpmSessionHelper.findProcessTasks(toolBpmSession, context, existingPi);
                for (BpmTask t : taskList) {
                    if (!hasText(rule.getProcessTaskName()) || rule.getProcessTaskName().equalsIgnoreCase(t.getTaskName())) {
                        Set<ProcessStateAction> actions = t.getCurrentProcessStateConfiguration().getActions();
                        for (ProcessStateAction a : actions) {
                            if (rule.getRunningProcessActionName().equals(a.getBpmName())) {
								ProcessToolBpmSessionHelper.performAction(toolBpmSession, context, a, t);
                                logger.info("Performed action " + rule.getId() + " on matched process id: " + existingPi.getInternalId());
                                break;
                            }
                        }
                    }
                }

            }
            if (existingPi != null && hasText(rule.getRepositoryAtomUrl())) {
                CmisAtomSessionFacade sessionFacade = new CmisAtomSessionFacade();
                String folderId = null;

                for (ProcessInstanceSimpleAttribute at : existingPi.getProcessSimpleAttributes()) {
					if (at.getKey().equals(rule.getFolderAttributeName())) {
						folderId = at.getValue();
						break;
					}
                }
                Folder mainFolder;
                if (folderId == null) {
                    mainFolder = sessionFacade.createFolderIfNecessary(nvl(rule.getNewFolderPrefix(), "") +
                            existingPi.getInternalId(), rule.getRootFolderPath());
                    if (hasText(rule.getSubFolder()))
                        mainFolder = sessionFacade.createFolderIfNecessary(rule.getSubFolder(), mainFolder.getPath());
                } else {
                    mainFolder = sessionFacade.getFolderById(folderId);
                }
                for (int i = 0; i < dir.list().length; ++i) {
                    String fileName = dir.list()[i];
                    if (!fileName.equals(".finish")) {
                        File file = new File(dir.getAbsolutePath() + "/" + fileName);
                        String mimeType = new MimetypesFileTypeMap().getContentType(file);
                        InputStream is = new FileInputStream(file);
                        try {
							long length = file.length();
							if (length > Integer.MAX_VALUE) {
								throw new IOException("Could not completely read file " + file.getName());
							}
							byte[] bytes = new byte[(int) length];
							int offset = 0;
							int numRead = 0;
							while (offset < bytes.length
									&& (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0) {
								offset += numRead;
							}
							if (offset < bytes.length) {
								throw new IOException("Could not completely read file " + file.getName());
							}
							sessionFacade.uploadDocument(file.getName(), mainFolder, bytes, mimeType, null);
						}
						finally {
                        	is.close();
							file.delete();
						}
                    }
                }
            }
        }
    }
}
