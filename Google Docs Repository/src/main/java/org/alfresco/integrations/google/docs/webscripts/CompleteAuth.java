
package org.alfresco.integrations.google.docs.webscripts;

import java.util.HashMap;
import java.util.Map;

import org.alfresco.integrations.google.docs.service.GoogleDocsService;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

public class CompleteAuth extends DeclarativeWebScript
{
    private GoogleDocsService googledocsService;

    private final static String PARAM_ACCESS_TOKEN = "access_token";

    private final static String MODEL_AUTHENTICATED = "authenticated";

    public void setGoogledocsService(GoogleDocsService googledocsService)
    {
        this.googledocsService = googledocsService;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache)
    {
        Map<String, Object> model = new HashMap<String, Object>();

        boolean authenticated = false;

        if (req.getParameter(PARAM_ACCESS_TOKEN) != null)
        {
            authenticated = googledocsService.completeAuthentication(req.getParameter(PARAM_ACCESS_TOKEN));
        }

        model.put(MODEL_AUTHENTICATED, authenticated);

        return model;
    }

}