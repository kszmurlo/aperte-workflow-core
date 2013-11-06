package pl.net.bluesoft.rnd.processtool.ui.basewidgets;

import pl.net.bluesoft.rnd.processtool.plugins.IBundleResourceProvider;
import pl.net.bluesoft.rnd.processtool.ui.widgets.ProcessHtmlWidget;
import pl.net.bluesoft.rnd.processtool.ui.widgets.annotations.AliasName;
import pl.net.bluesoft.rnd.processtool.ui.widgets.annotations.AperteDoc;
import pl.net.bluesoft.rnd.processtool.ui.widgets.annotations.AutoWiredProperty;
import pl.net.bluesoft.rnd.processtool.ui.widgets.annotations.ChildrenAllowed;
import pl.net.bluesoft.rnd.processtool.ui.widgets.impl.SimpleWidgetDataHandler;
import pl.net.bluesoft.rnd.processtool.web.widgets.impl.FileWidgetContentProvider;

/**
 * 
 * History process widget. 
 * 
 * Refactored for css layout
 * 
 * @author tlipski@bluesoft.net.pl
 * @author mpawlak@bluesoft.net.pl
 */
@AliasName(name = "ProcessHistory")
@AperteDoc(humanNameKey="widget.process_history.name", descriptionKey="widget.process_history.description")
@ChildrenAllowed(false)
public class ProcessHistoryWidget extends ProcessHtmlWidget {
	
	public ProcessHistoryWidget(IBundleResourceProvider bundleResourceProvider) {
		setContentProvider(new FileWidgetContentProvider("process-history.html", bundleResourceProvider));
		setDataHandler(new SimpleWidgetDataHandler());
	}
	
    @AutoWiredProperty(required = false)
    @AperteDoc(
            humanNameKey="widget.process_history.property.table.name",
            descriptionKey="widget.process_history.property.table.description"
    )
	private Boolean table;

}
