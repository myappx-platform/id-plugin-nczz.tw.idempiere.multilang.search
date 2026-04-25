/******************************************************************************
 * Copyright (C) 2024 iDempiere Community Contributors                        *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 *****************************************************************************/
package org.adempiere.webui.apps;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.adempiere.webui.panel.HeaderPanel;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Page;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.util.UiLifeCycle;
import org.zkoss.zul.Tree;

/**
 * UiLifeCycle listener that detects when a {@link GlobalSearch} component is
 * attached to the page, then replaces it with a multi-language-aware version
 * using deferred {@link Events#echoEvent(String, Component, Object)} to avoid
 * interfering with the ongoing {@code createSearchPanel()} execution.
 */
public class MultiLangSearchPatcher implements UiLifeCycle {

	private static final Logger log = Logger.getLogger(MultiLangSearchPatcher.class.getName());
	private static final String ON_MULTILANG_PATCH = "onMultiLangPatch";
	private static final String PATCHED_ATTR = "multilang.patched";

	// Reflection field names — centralized for upgrade maintenance
	private static final String FIELD_MENU_CONTROLLER = "menuController";
	private static final String FIELD_TREE = "tree";
	private static final String FIELD_GLOBAL_SEARCH = "globalSearch";

	@Override
	public void afterComponentAttached(Component comp, Page page) {
		if (!(comp instanceof GlobalSearch))
			return;

		// Schedule deferred patching — createSearchPanel() hasn't finished yet
		// (setId/setPlaceHolderText are called AFTER insertBefore which triggers this)
		Events.echoEvent(ON_MULTILANG_PATCH, comp, null);
		comp.addEventListener(ON_MULTILANG_PATCH, new EventListener<Event>() {
			@Override
			public void onEvent(Event event) throws Exception {
				Component target = event.getTarget();
				target.removeEventListener(ON_MULTILANG_PATCH, this);
				if (target.getPage() == null) return; // component already detached
				if (!"menuLookup".equals(target.getId())) return;
				if (target.getAttribute(PATCHED_ATTR) != null) return;
				target.setAttribute(PATCHED_ATTR, Boolean.TRUE);
				patchGlobalSearch((GlobalSearch) target);
			}
		});
	}

	private void patchGlobalSearch(GlobalSearch oldGs) {
		try {
			// 1. Extract Tree from old controller via reflection
			MenuSearchController oldCtrl = getField(oldGs, GlobalSearch.class, FIELD_MENU_CONTROLLER);
			Tree tree = getField(oldCtrl, MenuSearchController.class, FIELD_TREE);

			// 2. Create multi-language controller and new GlobalSearch
			MultiLangMenuSearchController newCtrl = new MultiLangMenuSearchController(tree);
			GlobalSearch newGs = new GlobalSearch(newCtrl);
			newGs.setId("menuLookup");
			newGs.setPlaceHolderText("Alt+G");
			newGs.setTooltipText("Alt+G");

			// 3. Replace in DOM — must detach old BEFORE inserting new (duplicate ID not allowed)
			Component parent = oldGs.getParent();
			Component nextSibling = oldGs.getNextSibling();
			oldGs.detach();
			if (nextSibling != null)
				parent.insertBefore(newGs, nextSibling);
			else
				parent.appendChild(newGs);
			newGs.setAttribute(PATCHED_ATTR, Boolean.TRUE); // prevent re-patching the new one

			// 4. Update HeaderPanel.globalSearch field for Alt+G support
			Component hp = newGs.getParent();
			while (hp != null && !(hp instanceof HeaderPanel))
				hp = hp.getParent();
			if (hp != null) {
				setField(hp, HeaderPanel.class, FIELD_GLOBAL_SEARCH, newGs);
			}

			log.info("Multi-Language Global Search patched successfully");
		} catch (Exception e) {
			log.log(Level.WARNING, "Failed to patch GlobalSearch — falling back to default search", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T getField(Object obj, Class<?> clazz, String fieldName) throws Exception {
		Field f = clazz.getDeclaredField(fieldName);
		f.setAccessible(true);
		return (T) f.get(obj);
	}

	private static void setField(Object obj, Class<?> clazz, String fieldName, Object value) throws Exception {
		Field f = clazz.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(obj, value);
	}

	@Override public void afterComponentDetached(Component comp, Page prevpage) {}
	@Override public void afterComponentMoved(Component parent, Component child, Component prevparent) {}
	@Override public void afterPageAttached(Page page, org.zkoss.zk.ui.Desktop desktop) {}
	@Override public void afterPageDetached(Page page, org.zkoss.zk.ui.Desktop prevdesktop) {}
	@Override public void afterShadowAttached(org.zkoss.zk.ui.ShadowElement shadow, Component host) {}
	@Override public void afterShadowDetached(org.zkoss.zk.ui.ShadowElement shadow, Component prevhost) {}
}
