package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.util.xml.NanoXmlBuilder;
import com.intellij.util.xml.NanoXmlUtil;
import net.n3.nanoxml.IXMLEntityResolver;
import net.n3.nanoxml.NonValidator;
import net.n3.nanoxml.StdXMLParser;
import net.n3.nanoxml.StdXMLReader;
import net.n3.nanoxml.XMLException;
import net.n3.nanoxml.XMLParseException;
import org.jetbrains.annotations.NotNull;

import java.io.Reader;
import java.io.StringReader;

final class SafeNanoXmlParser {
    private static final EmptyValidator EMPTY_VALIDATOR = new EmptyValidator();
    private static final EmptyEntityResolver EMPTY_ENTITY_RESOLVER = new EmptyEntityResolver();

    private SafeNanoXmlParser() {
    }

    static boolean parse(@NotNull CharSequence content, @NotNull NanoXmlBuilder builder) {
        StdXMLParser parser = new StdXMLParser(
                new StdXMLReader(new StringReader(content.toString())),
                builder,
                EMPTY_VALIDATOR,
                EMPTY_ENTITY_RESOLVER);
        try {
            parser.parse();
            return true;
        } catch (NanoXmlUtil.ParserStoppedXmlException ignored) {
            return false;
        } catch (XMLException exception) {
            if (exception.getException() instanceof ProcessCanceledException canceled) {
                throw canceled;
            }
            return false;
        }
    }

    private static final class EmptyValidator extends NonValidator {
    }

    private static final class EmptyEntityResolver implements IXMLEntityResolver {
        @Override
        public void addInternalEntity(String name, String value) {
        }

        @Override
        public void addExternalEntity(String name, String publicId, String systemId) {
        }

        @Override
        public Reader getEntity(StdXMLReader xmlReader, String name) throws XMLParseException {
            return new StringReader("");
        }

        @Override
        public boolean isExternalEntity(String name) {
            return false;
        }
    }
}
