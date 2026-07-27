package com.developerfromjokela.opencarwings.sms.utils;

import java.net.URI;
import java.net.URISyntaxException;

public final class URLUtils {
    public static URI appendUri(URI oldUri, String appendQuery) throws URISyntaxException {
        return new URI(oldUri.getScheme(), oldUri.getAuthority(), oldUri.getPath(),
                oldUri.getQuery() == null ? appendQuery : oldUri.getQuery() + "&" + appendQuery, oldUri.getFragment());
    }
}
