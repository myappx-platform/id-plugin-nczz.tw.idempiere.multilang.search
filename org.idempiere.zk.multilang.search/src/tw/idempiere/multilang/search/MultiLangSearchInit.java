/******************************************************************************
 * Copyright (C) 2024 iDempiere Community Contributors                        *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 *****************************************************************************/
package tw.idempiere.multilang.search;

import org.adempiere.webui.apps.GlobalSearch;
import org.adempiere.webui.apps.MenuSearchController;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.zkoss.zk.ui.WebApp;
import org.zkoss.zk.ui.util.WebAppInit;

/**
 * Registers the {@link MultiLangSearchPatcher} as a UiLifeCycle listener
 * when the ZK WebApp initializes. Registered via metainfo/zk/config.xml.
 */
public class MultiLangSearchInit implements WebAppInit {

	private static final Logger log = Logger.getLogger(MultiLangSearchInit.class.getName());
	private static final String REGISTERED_ATTR = "multilang.search.registered";

	@Override
	public void init(WebApp wapp) throws Exception {
		if (wapp.getAttribute(REGISTERED_ATTR) != null)
			return;
		try {
			wapp.getConfiguration().addListener(MultiLangSearchPatcher.class);
			wapp.setAttribute(REGISTERED_ATTR, Boolean.TRUE);
			log.info("Multi-Language Global Search plugin initialized");
		} catch (Exception e) {
			log.log(Level.WARNING, "Failed to register MultiLangSearchPatcher", e);
		}
	}
}
