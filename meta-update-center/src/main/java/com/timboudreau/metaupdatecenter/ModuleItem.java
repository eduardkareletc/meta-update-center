package com.timboudreau.metaupdatecenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import static com.mastfrog.util.collections.CollectionUtils.map;
import com.sun.org.apache.xml.internal.serialize.OutputFormat;
import com.sun.org.apache.xml.internal.serialize.XMLSerializer;
import com.timboudreau.metaupdatecenter.borrowed.SpecificationVersion;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 *
 * @author Tim Boudreau
 */
public final class ModuleItem implements Comparable<ModuleItem> {

    private final String codeNameBase;
    private final String hash;
    private final Map<String, Object> metadata;
    private final ZonedDateTime downloaded;
    private final boolean useOriginalURL;
    private final String from;
    private final ZonedDateTime lastModified;

    @JsonCreator
    public ModuleItem(@JsonProperty("codeNameBase") String codeNameBase,
            @JsonProperty("hash") String hash,
            @JsonProperty("metadata") Map<String, Object> info,
            @JsonProperty("downloaded") ZonedDateTime downloaded,
            @JsonProperty("useOriginalURL") boolean useOriginalURL,
            @JsonProperty("lastModified") ZonedDateTime lastModified,
            @JsonProperty("from") String from) {
        this.lastModified = lastModified;
        this.codeNameBase = codeNameBase;
        this.hash = hash;
        this.metadata = info;
        this.downloaded = downloaded;
        this.useOriginalURL = useOriginalURL;
        this.from = from;
    }

    public static ModuleItem fromFile(File file, ObjectMapper mapper) throws IOException {
        return mapper.readValue(file, ModuleItem.class);
    }

    public boolean isUseOriginalURL() {
        return useOriginalURL;
    }

    public Map<String, Object> getMetadata() {
        return new ImmutableMap.Builder<String, Object>().putAll(metadata).build();
    }

    public Map<String, Object> toMap() {
        return map("cnb").to(getCodeNameBase())
                .map("downloaded").to(getDownloaded())
                .map("version").finallyTo(getVersion());
    }

    public ZonedDateTime getWhen() {
        if (lastModified != null && lastModified.toInstant().toEpochMilli() != 0L) {
            return lastModified;
        }
        return getDownloaded();
    }

    public ZonedDateTime getLastModified() {
        return lastModified;
    }

    public ZonedDateTime getDownloaded() {
        return downloaded;
    }

    public String getFrom() {
        return from;
    }

    public String getCodeNameBase() {
        return codeNameBase;
    }

    public String getHash() {
        return hash;
    }

    public String toString() {
        return getCodeNameBase() + " " + getVersion() + " downloaded " + 
                Headers.ISO2822DateFormat.format(getDownloaded()) + " from " + getFrom();
    }

    public SpecificationVersion getVersion() {
        String val = (String) getManifest().get("OpenIDE-Module-Specification-Version");
        return val == null ? new SpecificationVersion("0.0.0") : new SpecificationVersion(val);
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Map<String, Object> getManifest() {
        return (Map<String, Object>) getMetadata().get("manifest");
    }

    public String getName() {
        return (String) getManifest().get("OpenIDE-Module-Name");
    }

    public String getDescription() {
        String result = (String) getManifest().get("OpenIDE-Module-Long-Description");
        if (result == null) {
            result = (String) getManifest().get("OpenIDE-Module-Short-Description");
        }
        return result == null ? "" : "<undefined>".equals(result) ? "" : result;
    }

    @Override
    public int compareTo(ModuleItem o) {
        // Highest specification version first, then newest first
        // So, compare foreign value against ours to do reverse compare
        SpecificationVersion mine = getVersion();
        SpecificationVersion theirs = o.getVersion();
        int result = theirs.compareTo(mine);
        if (result == 0) {
            result = o.getDownloaded().compareTo(o.getDownloaded());
        }
        return result;
    }

    private String xml;

    public String toXML(PathFactory paths, String base) throws ParserConfigurationException, IOException, TransformerException {
        if (xml != null) {
            return xml;
        }
        Map<String, Object> meta = new HashMap<>(getMetadata());
        if (!useOriginalURL) {
            URL nue = paths.constructURL(Path.builder().add(base).add(getCodeNameBase()).add(hash + ".nbm").create(), false);
            meta.put("distribution", nue.toString());
        }
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document document = dbf.newDocumentBuilder().newDocument();
        Element moduleNode = document.createElement("module");
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof String) {
                moduleNode.setAttribute(key, (String) e.getValue());
            }
        }
        Map<String, Object> manifest = getManifest();
        assert manifest != null : "Manifest is null " + this.codeNameBase + " " + this.hash;
        Element manifestNode = document.createElement("manifest");
        for (Map.Entry<String, Object> e : manifest.entrySet()) {
            String key = e.getKey();
            if (e.getValue() instanceof String) {
                manifestNode.setAttribute(key, (String) e.getValue());
            }
        }
        document.appendChild(moduleNode);
        moduleNode.appendChild(manifestNode);

        return xml = nodeToString(document);
    }

    public static String nodeToString(Document document) throws IOException, TransformerConfigurationException, TransformerException {
        OutputFormat format = new OutputFormat("XML", "UTF-8", true);
        format.setOmitXMLDeclaration(true);
        format.setStandalone(false);
        format.setPreserveEmptyAttributes(true);
        format.setAllowJavaNames(true);
        format.setPreserveSpace(false);
        format.setIndenting(true);
        format.setIndent(4);
        format.setLineWidth(80);
        Writer out = new StringWriter();
        XMLSerializer serializer = new XMLSerializer(out, format);
        serializer.serialize(document);
        return out.toString();
    }
}
