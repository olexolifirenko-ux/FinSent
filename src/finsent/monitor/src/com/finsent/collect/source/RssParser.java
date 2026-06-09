package com.finsent.collect.source;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import com.finsent.core.Times;

/**
 * Parses RSS and Atom feed bytes into {@link RssItem}s (ports the parsing half of Python
 * {@code collect._parse_rss_items}/{@code _parse_atom_entries} + the date helpers). Pure and
 * dependency-free so it can be unit-tested on fixtures; HTTP, watermark filtering and ObjectNode
 * construction live in {@link RssSource}. Handles Google-News RSS ({@code <source url=..>} gives
 * the real article URL and source name, and the {@code " - Source"} title suffix is stripped).
 *
 * <p>The XML parser is hardened against XXE / entity-expansion attacks (DOCTYPE and external
 * entities disabled). Namespace-unaware parsing matches feeds that use a default (unprefixed)
 * Atom namespace, which is the case for the configured feeds.
 */
public final class RssParser
{
    private static final String ATOM_FALLBACK_REL = "alternate";

    /** One parsed feed entry; {@code sourceName} is non-empty only for Google-News items. */
    public record RssItem(String title, String url, String desc, String pubIso, String sourceName)
    {
    }

    private RssParser()
    {
    }

    public static List<RssItem> parse(String xml)
    {
        return xml == null ? List.of() : parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** Parse feed bytes; returns an empty list on any parse failure (malformed feed). */
    public static List<RssItem> parse(byte[] xml)
    {
        List<RssItem> items = List.of();
        try
        {
            Document doc = newBuilder().parse(new ByteArrayInputStream(xml));
            Element root = doc.getDocumentElement();
            Element channel = firstChild(root, "channel");
            boolean atom = channel == null
                    && (root.getTagName().toLowerCase(Locale.ROOT).contains("feed") || hasElement(root, "entry"));
            items = atom ? parseAtom(root) : parseRss(root, channel);
        }
        catch (Exception malformedFeed)
        {
            items = List.of();
        }
        return items;
    }

    private static List<RssItem> parseRss(Element root, Element channel)
    {
        Element container = channel != null ? channel : root;
        List<RssItem> parsed = new ArrayList<>();
        NodeList items = container.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++)
        {
            parsed.add(parseRssItem((Element) items.item(i)));
        }
        return parsed;
    }

    private static RssItem parseRssItem(Element item)
    {
        String title = directChildText(item, "title");
        String url = directChildText(item, "link");
        String desc = directChildText(item, "description");
        String pubRaw = directChildText(item, "pubDate");
        String pubIso = pubRaw.isEmpty() ? "" : rssPubToIso(pubRaw);

        String sourceName = "";
        Element source = firstChild(item, "source");
        if (source != null)
        {
            sourceName = text(source);
            String sourceUrl = source.getAttribute("url");
            if (!sourceUrl.isEmpty())
            {
                url = sourceUrl;
            }
            String suffix = " - " + sourceName;
            if (!sourceName.isEmpty() && title.endsWith(suffix))
            {
                title = title.substring(0, title.length() - suffix.length()).trim();
            }
        }
        return new RssItem(title, url, desc, pubIso, sourceName);
    }

    private static List<RssItem> parseAtom(Element root)
    {
        List<RssItem> parsed = new ArrayList<>();
        NodeList entries = root.getElementsByTagName("entry");
        for (int i = 0; i < entries.getLength(); i++)
        {
            parsed.add(parseAtomEntry((Element) entries.item(i)));
        }
        return parsed;
    }

    private static RssItem parseAtomEntry(Element entry)
    {
        String title = directChildText(entry, "title");
        String url = atomLink(entry);
        String desc = directChildText(entry, "summary");
        if (desc.isEmpty())
        {
            desc = directChildText(entry, "content");
        }
        String pubRaw = directChildText(entry, "published");
        if (pubRaw.isEmpty())
        {
            pubRaw = directChildText(entry, "updated");
        }
        String pubIso = pubRaw.isEmpty() ? "" : atomDateToIso(pubRaw);
        return new RssItem(title, url, desc, pubIso, "");
    }

    /** Atom {@code <link>}: prefer {@code rel="alternate"}, else the first href. */
    private static String atomLink(Element entry)
    {
        String url = "";
        for (Element link : directChildren(entry, "link"))
        {
            String href = link.getAttribute("href");
            String rel = link.getAttribute("rel");
            if (rel.isEmpty())
            {
                rel = ATOM_FALLBACK_REL;
            }
            if (rel.equals(ATOM_FALLBACK_REL) && !href.isEmpty())
            {
                url = href;
                break;
            }
            if (url.isEmpty() && !href.isEmpty())
            {
                url = href;
            }
        }
        return url;
    }

    private static String rssPubToIso(String pubDate)
    {
        String iso;
        try
        {
            Instant instant = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            iso = Times.formatUtcIso(instant);
        }
        catch (RuntimeException badDate)
        {
            iso = "";
        }
        return iso;
    }

    private static String atomDateToIso(String dateStr)
    {
        String iso;
        try
        {
            iso = Times.formatUtcIso(Times.parseIso(dateStr));
        }
        catch (RuntimeException badDate)
        {
            iso = "";
        }
        return iso;
    }

    private static boolean hasElement(Element root, String tag)
    {
        return root.getElementsByTagName(tag).getLength() > 0;
    }

    private static Element firstChild(Element parent, String tag)
    {
        Element found = null;
        List<Element> children = directChildren(parent, tag);
        if (!children.isEmpty())
        {
            found = children.get(0);
        }
        return found;
    }

    private static String directChildText(Element parent, String tag)
    {
        Element child = firstChild(parent, tag);
        return child == null ? "" : text(child);
    }

    private static List<Element> directChildren(Element parent, String tag)
    {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && ((Element) node).getTagName().equals(tag))
            {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String text(Element element)
    {
        String content = element.getTextContent();
        return content == null ? "" : content.trim();
    }

    private static DocumentBuilder newBuilder() throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(QUIET_ERRORS);
        return builder;
    }

    /** Rethrow parse errors (so {@link #parse} returns empty) without printing to stderr. */
    private static final ErrorHandler QUIET_ERRORS = new ErrorHandler()
    {
        @Override
        public void warning(SAXParseException ignored)
        {
            // Ignored: warnings do not affect the parsed result.
        }

        @Override
        public void error(SAXParseException error) throws SAXParseException
        {
            throw error;
        }

        @Override
        public void fatalError(SAXParseException fatal) throws SAXParseException
        {
            throw fatal;
        }
    };
}
