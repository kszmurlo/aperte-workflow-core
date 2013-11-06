package pl.net.bluesoft.rnd.processtool.ui.basewidgets;

import pl.net.bluesoft.rnd.processtool.plugins.IBundleResourceProvider;
import pl.net.bluesoft.rnd.processtool.ui.widgets.ProcessHtmlWidget;
import pl.net.bluesoft.rnd.processtool.ui.widgets.annotations.AliasName;
import pl.net.bluesoft.rnd.processtool.ui.widgets.impl.SimpleWidgetDataHandler;
import pl.net.bluesoft.rnd.processtool.web.widgets.impl.FileWidgetContentProvider;

/**
 * User: POlszewski
 * Date: 2011-09-02
 * Time: 14:07:11
 */
@AliasName(name = "UserSubstitutionRequest")
public class UserSubstitutionRequestWidget extends ProcessHtmlWidget
//public class UserSubstitutionRequestWidget extends BaseProcessToolWidget implements ProcessToolVaadinRenderable, ProcessToolDataWidget
{
	public UserSubstitutionRequestWidget(IBundleResourceProvider bundleResourceProvider) {
		setContentProvider(new FileWidgetContentProvider("process-subst-request.html", bundleResourceProvider));
		setDataHandler(new SimpleWidgetDataHandler());
	}
}