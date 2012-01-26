package pl.net.bluesoft.rnd.pt.ext.vaadin;


import com.vaadin.Application;
import com.vaadin.terminal.gwt.server.HttpServletRequestListener;
import com.vaadin.terminal.gwt.server.WebApplicationContext;
import org.apache.commons.lang.StringUtils;
import pl.net.bluesoft.rnd.processtool.ProcessToolContext;
import pl.net.bluesoft.rnd.processtool.ProcessToolContextCallback;
import pl.net.bluesoft.rnd.processtool.i18n.DefaultI18NSource;
import pl.net.bluesoft.rnd.processtool.plugins.ProcessToolRegistry;
import pl.net.bluesoft.rnd.util.vaadin.VaadinUtility;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Basic class for editor application which provides integration with rest of
 * the Aperte Workflow infrastructure
 */
public class GenericEditorApplication extends Application implements HttpServletRequestListener {

    private static ThreadLocal<GenericEditorApplication> current = new ThreadLocal<GenericEditorApplication>();

    /**
     * Get current application object associated with this thread of execution
     * @return current application
     */
    public static GenericEditorApplication getCurrent() {
        return current.get();
    }

    /**
     * Get current {@link ProcessToolRegistry}
     * @return current registry
     */
    public static ProcessToolRegistry getRegistry() {
        WebApplicationContext webCtx = (WebApplicationContext) getCurrent().getContext();
        ServletContext sc = webCtx.getHttpSession().getServletContext();
        return (ProcessToolRegistry) sc.getAttribute(ProcessToolRegistry.class.getName());
    }

    @Override
    public void init() {
        current.set(this);
    }

    @Override
    public void onRequestStart(final HttpServletRequest request, HttpServletResponse response) {
        current.set(this);

        VaadinUtility.setThreadApplication(this);
        VaadinUtility.setThreadI18nSource(new DefaultI18NSource(request.getLocale()));

        // Setting ProcessToolContext was taken from ProcessToolVaadinApplicationPortlet2
        // to preserve functionality used in portlet based Vaadin applications
        ServletContext servletContext = request.getSession().getServletContext();
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            ProcessToolRegistry registry = (ProcessToolRegistry) servletContext.getAttribute(ProcessToolRegistry.class.getName());
            registry.withProcessToolContext(new ProcessToolContextCallback() {
                @Override
                public void withContext(ProcessToolContext ctx) {
                    ProcessToolContext.Util.setProcessToolContextForThread(ctx);

                }
            });
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @Override
    public void onRequestEnd(HttpServletRequest request, HttpServletResponse response) {
        ProcessToolContext ctx = ProcessToolContext.Util.getProcessToolContextFromThread();
        if (ctx != null) {
            ProcessToolContext.Util.removeProcessToolContextForThread(ctx);
        }

        VaadinUtility.setThreadI18nSource(null);
        VaadinUtility.setThreadApplication(null);

        current.remove();
    }
    
    protected String getStringParameterByName(String paramterName, Map<String, String[]> paramterMap) {
        String[] value = paramterMap.get(paramterName);
        if (value != null && value.length > 0 && !StringUtils.isEmpty(value[0])) {
            return value[0];
        }
        return null;
    }

}
