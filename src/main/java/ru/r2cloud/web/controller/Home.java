package ru.r2cloud.web.controller;

import ru.r2cloud.web.AbstractHttpController;
import ru.r2cloud.web.ModelAndView;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;

public class Home extends AbstractHttpController {

	@Override
	public ModelAndView doGet(IHTTPSession session) {
		return new ModelAndView("index");
	}

	@Override
	public String getRequestMappingURL() {
		return "/admin/";
	}

}