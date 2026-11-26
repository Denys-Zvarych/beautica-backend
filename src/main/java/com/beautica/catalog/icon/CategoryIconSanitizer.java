package com.beautica.catalog.icon;

import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.common.exception.CategoryIconInvalidException.Reason;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;

/**
 * B539 (SVC-59) — validates an uploaded category icon against the allow-list and returns its canonical bytes. Pure (no
 * Spring, no I/O beyond the in-memory parse), thread-safe (a fresh parser per call).
 *
 * <p><b>Reject, never strip</b>: any rule break throws {@link CategoryIconInvalidException} with its {@link Reason}, so
 * the operator sees why. Only comments, the XML declaration and the inert root metadata
 * ({@link IconAttributeRules#isInertRootAttribute}) are accepted and left out of the output.
 *
 * <p><b>Parser hardening</b>: DOCTYPE disallowed (no entity can be declared, so no XXE / billion laughs), external
 * entities and DTD loading off, {@link XMLConstants#FEATURE_SECURE_PROCESSING} on, no XInclude. The input is decoded
 * as strict UTF-8 first and handed to the parser as characters, so an {@code encoding="…"} declaration cannot change
 * the decoding.
 *
 * <p><b>Canonical form</b>: allow-listed elements only, attributes sorted by name after {@code xmlns} and
 * {@code viewBox}, whitespace in values collapsed, {@code fill}/{@code stroke} spelled {@code currentColor} /
 * {@code none}, no comments, no declaration, empty elements self-closed. Re-sanitising the output yields the same bytes.
 */
public final class CategoryIconSanitizer {

    /** SVC-59 size cap on the raw upload, in UTF-8 bytes. */
    public static final int MAX_BYTES = 16 * 1024;

    /**
     * Deepest element nesting accepted (root = depth 1). A real icon nests a few {@code <g>} levels; the cap keeps the
     * recursive canonical writer far from a stack overflow on a hostile 16 KiB of nested {@code <g>}. Deeper → reason
     * {@code FORBIDDEN_ELEMENT} (no new reason code: SVC-59 and the portal map only the existing set).
     */
    static final int MAX_DEPTH = 32;

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String XMLNS = "xmlns";
    private static final String VIEW_BOX = "viewBox";
    private static final String ROOT = "svg";
    private static final char BOM = '﻿';
    private static final Set<String> ELEMENTS =
            Set.of(ROOT, "g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon");

    private CategoryIconSanitizer() {
    }

    /**
     * @param raw the uploaded bytes (UTF-8)
     * @return the canonical bytes and their sha256
     * @throws CategoryIconInvalidException with the first rule the upload breaks
     */
    public static SanitizedIcon sanitize(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new CategoryIconInvalidException(Reason.NOT_SVG);
        }
        if (raw.length > MAX_BYTES) {
            throw new CategoryIconInvalidException(Reason.TOO_LARGE);
        }
        Element root = parse(decodeStrictUtf8(raw)).getDocumentElement();
        if (!ROOT.equals(root.getLocalName()) || !SVG_NS.equals(root.getNamespaceURI())) {
            throw new CategoryIconInvalidException(Reason.NOT_SVG);
        }
        if (!IconAttributeRules.isExpectedViewBox(root.hasAttribute(VIEW_BOX) ? root.getAttribute(VIEW_BOX) : null)) {
            throw new CategoryIconInvalidException(Reason.BAD_VIEWBOX);
        }
        requireNoForeignPrologNodes(root.getOwnerDocument());
        StringBuilder out = new StringBuilder(raw.length);
        writeElement(root, 1, out);
        return SanitizedIcon.of(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeStrictUtf8(byte[] raw) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
            return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
        } catch (CharacterCodingException ex) {
            throw new CategoryIconInvalidException(Reason.NOT_SVG);
        }
    }

    private static Document parse(String text) {
        try {
            DocumentBuilder builder = hardenedFactory().newDocumentBuilder();
            builder.setErrorHandler(SilentErrorHandler.INSTANCE);
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not allowed");
            });
            return builder.parse(new InputSource(new StringReader(text)));
        } catch (SAXException | IOException ex) {
            // A DOCTYPE, an undeclared prefix or any well-formedness error.
            throw new CategoryIconInvalidException(Reason.NOT_SVG);
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("XML parser does not support the hardening features", ex);
        }
    }

    private static DocumentBuilderFactory hardenedFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** Comments around the root are dropped; a processing instruction there is refused. */
    private static void requireNoForeignPrologNodes(Document document) {
        for (Node node = document.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                throw new CategoryIconInvalidException(Reason.FORBIDDEN_ELEMENT);
            }
        }
    }

    private static void writeElement(Element element, int depth, StringBuilder out) {
        boolean isRoot = depth == 1;
        if (depth > MAX_DEPTH || !SVG_NS.equals(element.getNamespaceURI()) || !ELEMENTS.contains(element.getLocalName())
                || (!isRoot && ROOT.equals(element.getLocalName()))) {
            throw new CategoryIconInvalidException(Reason.FORBIDDEN_ELEMENT);
        }
        TreeMap<String, String> attributes = canonicalAttributes(element, isRoot);
        out.append('<').append(element.getLocalName());
        if (isRoot) {
            out.append(" xmlns=\"").append(SVG_NS).append("\" viewBox=\"").append(IconAttributeRules.VIEW_BOX)
                    .append('"');
        }
        attributes.forEach((name, value) -> out.append(' ').append(name).append("=\"").append(escape(value))
                .append('"'));
        int lengthBeforeChildren = out.length();
        out.append('>');
        writeChildren(element, depth, out);
        if (out.length() == lengthBeforeChildren + 1) {
            out.setLength(lengthBeforeChildren);
            out.append("/>");
        } else {
            out.append("</").append(element.getLocalName()).append('>');
        }
    }

    private static void writeChildren(Element element, int depth, StringBuilder out) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE -> writeElement((Element) child, depth + 1, out);
                case Node.COMMENT_NODE -> { /* dropped from the canonical bytes */ }
                case Node.TEXT_NODE -> {
                    if (!child.getNodeValue().isBlank()) {
                        throw new CategoryIconInvalidException(Reason.FORBIDDEN_ELEMENT);
                    }
                }
                default -> throw new CategoryIconInvalidException(Reason.FORBIDDEN_ELEMENT);
            }
        }
    }

    /** External refs are checked across ALL attributes first, so {@code xlink:href} reports EXTERNAL_REF. */
    private static TreeMap<String, String> canonicalAttributes(Element element, boolean isRoot) {
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Attr attr = (Attr) map.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) {
                IconAttributeRules.requireNoExternalRef(localNameOf(attr), attr.getValue());
            } else if (XLINK_NS.equals(attr.getValue())) {
                // The xlink namespace exists only to link; any other declaration's value is a namespace name, not a
                // reference, and is judged below (FORBIDDEN_ATTRIBUTE).
                throw new CategoryIconInvalidException(Reason.EXTERNAL_REF);
            }
        }
        TreeMap<String, String> kept = new TreeMap<>();
        for (int i = 0; i < map.getLength(); i++) {
            Attr attr = (Attr) map.item(i);
            String name = attr.getName();
            if (isSvgNamespaceDeclaration(attr) || (isRoot && VIEW_BOX.equals(name))
                    || (isRoot && IconAttributeRules.isInertRootAttribute(name))) {
                continue;
            }
            if (attr.getNamespaceURI() != null) {
                // xmlns:prefix declarations, xlink:*, xml:* and any other namespaced attribute.
                throw new CategoryIconInvalidException(Reason.FORBIDDEN_ATTRIBUTE);
            }
            kept.put(name, IconAttributeRules.canonicalValue(name, attr.getValue()));
        }
        return kept;
    }

    private static boolean isSvgNamespaceDeclaration(Attr attr) {
        return XMLNS.equals(attr.getName()) && SVG_NS.equals(attr.getValue());
    }

    private static String localNameOf(Attr attr) {
        return attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Keeps the parser from printing to stderr; every error and fatal error aborts the parse. */
    private enum SilentErrorHandler implements ErrorHandler {
        INSTANCE;

        @Override
        public void warning(SAXParseException exception) {
            // ignored: warnings never change the verdict
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
