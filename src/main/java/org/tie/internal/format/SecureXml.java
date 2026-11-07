package org.tie.internal.format;

import javax.xml.stream.XMLInputFactory;

/** StAX factories that never process DTDs or resolve external entities (AC-35). */
public final class SecureXml {

    private SecureXml() {
    }

    public static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }
}
