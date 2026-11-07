package org.tie.config;

/** Per-format options of a layout; a missing format uses its defaults. */
public record FormatOptions(CsvOptions csv, XlsxOptions xlsx, XmlOptions xml, JsonOptions json) {

    public static final FormatOptions DEFAULTS = new FormatOptions(null, null, null, null);

    public FormatOptions {
        csv = csv == null ? CsvOptions.DEFAULTS : csv;
        xlsx = xlsx == null ? XlsxOptions.DEFAULTS : xlsx;
        xml = xml == null ? XmlOptions.DEFAULTS : xml;
        json = json == null ? JsonOptions.DEFAULTS : json;
    }

    public FormatOptions withCsv(CsvOptions csv) {
        return new FormatOptions(csv, xlsx, xml, json);
    }

    public FormatOptions withXlsx(XlsxOptions xlsx) {
        return new FormatOptions(csv, xlsx, xml, json);
    }

    public FormatOptions withXml(XmlOptions xml) {
        return new FormatOptions(csv, xlsx, xml, json);
    }

    public FormatOptions withJson(JsonOptions json) {
        return new FormatOptions(csv, xlsx, xml, json);
    }
}
