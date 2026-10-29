package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #80: a LICENSE file exists at the repository root,
 * and pom.xml declares the same license.
 */
class LicenseTest {

    private static final String NAME = "Apache License, Version 2.0";
    private static final String URL = "https://www.apache.org/licenses/LICENSE-2.0";

    @Test
    void licenseFileExistsAndPomDeclaresTheSameLicense() throws Exception {
        assertThat(Path.of("LICENSE")).as("LICENSE exists at the repository root").isRegularFile();
        assertThat(Files.readString(Path.of("LICENSE")))
                .as("LICENSE is the Apache 2.0 text")
                .contains("Apache License", "Version 2.0, January 2004");

        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        NodeList licenses = pom.getElementsByTagName("license");
        assertThat(licenses.getLength()).as("pom.xml declares one license").isEqualTo(1);

        Element license = (Element) licenses.item(0);
        assertThat(text(license, "name")).as("pom.xml license name matches LICENSE").isEqualTo(NAME);
        assertThat(text(license, "url")).as("pom.xml license url").isEqualTo(URL);
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent().trim();
    }
}
