package com.caseware.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Guards NFR-07: no runtime dependencies; only JUnit 5 and AssertJ, in test scope. */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class PomDependencyPolicyTest {

    private record Dependency(String groupId, String artifactId, String scope) { }

    @Test
    @DisplayName("pom.xml declares only JUnit 5 and AssertJ, both in test scope")
    void onlyTestScopedJUnitAndAssertJ() throws Exception {
        List<Dependency> deps = projectDependencies(Path.of("pom.xml"));

        assertThat(deps).isNotEmpty();
        assertThat(deps).allSatisfy(d -> {
            assertThat(d.scope()).as("scope of %s", d.artifactId()).isEqualTo("test");
            assertThat(d.groupId()).isIn("org.junit.jupiter", "org.assertj");
        });
    }

    /** Direct children of {@code <project><dependencies>}; managed (BOM) entries are excluded. */
    private static List<Dependency> projectDependencies(Path pom) throws Exception {
        Element project = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        List<Dependency> result = new ArrayList<>();
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element deps && deps.getTagName().equals("dependencies")) {
                NodeList list = deps.getElementsByTagName("dependency");
                for (int j = 0; j < list.getLength(); j++) {
                    Element d = (Element) list.item(j);
                    result.add(new Dependency(text(d, "groupId"), text(d, "artifactId"), text(d, "scope")));
                }
            }
        }
        return result;
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? "compile" : n.item(0).getTextContent().trim();
    }
}
