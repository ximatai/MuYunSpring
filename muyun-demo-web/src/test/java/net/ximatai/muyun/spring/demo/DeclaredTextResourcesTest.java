package net.ximatai.muyun.spring.demo;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers production declarations, including sources absent from both language bundles. */
class DeclaredTextResourcesTest {
    private static final Pattern ARGUMENT = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    @Test
    void allProductionCodeTitleEnumsHaveEnglishResources() throws Exception {
        var english = messages();
        var enums = parse(productionSources()).enumTypes();
        assertThat(enums).isNotEmpty();
        var sources = new ArrayList<TextReference>();
        for (var name : enums) {
            Class<?> type = Class.forName(name);
            if (!CodeTitleEnum.class.isAssignableFrom(type)) continue;
            for (var constant : type.getEnumConstants()) {
                var value = (CodeTitleEnum) constant;
                sources.add(TextReference.code("enum/" + type.getName() + "/" + value.getCode() + "/title", value.getTitle()));
            }
        }
        assertThat(sources).isNotEmpty();
        assertEnglish(sources, english);
    }

    @Test
    void allLiteralProductionTextReferencesIncludingDomainRejectionsHaveEnglishResources() throws Exception {
        var declarations = parse(productionSources());
        assertThat(declarations.textKeys()).isNotEmpty();
        var english = messages();
        assertEnglishKeys(declarations.textKeys(), english);
        assertEnglish(declarations.texts(), english);
    }

    @Test
    void declarationScanFindsUnregisteredSourcesAndNestedEnumsWithoutReadingComments() throws Exception {
        var declarations = parse(List.of(source("Example.java", """
                package example;
                // TextReference.code("comment.only", "注释");
                class Example {
                    enum State implements CodeTitleEnum { READY }
                    Object text = net.ximatai.muyun.spring.common.i18n.TextReference
                        .code("unregistered.rejection", "拒绝 {name}");
                    Object constantOriginal = TextReference.code("constant.original", ORIGINAL);
                    Object dynamic = TextReference.code(sourceId, original);
                }
                """)));
        assertThat(declarations.enumTypes()).containsExactly("example.Example$State");
        assertThat(declarations.textKeys()).containsExactly("unregistered.rejection", "constant.original");
        assertThat(declarations.texts()).containsExactly(TextReference.code("unregistered.rejection", "拒绝 {name}"));
        assertThatThrownBy(() -> assertEnglish(declarations.texts(), Map.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("unregistered.rejection");
        assertThatThrownBy(() -> assertEnglish(declarations.texts(), Map.of("unregistered.rejection", "Rejected {other}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("name");
    }

    private static void assertEnglish(List<TextReference> sources, Map<String, String> english) {
        assertEnglishKeys(sources.stream().map(TextReference::sourceId).toList(), english);
        for (var source : sources) {
            String translated = english.get(source.sourceId());
            assertThat(translated).as(source.sourceId()).isNotBlank().doesNotContainPattern("[\\p{IsHan}]");
            assertThat(arguments(translated)).as(source.sourceId()).isEqualTo(arguments(source.originalText()));
        }
    }

    private static void assertEnglishKeys(List<String> keys, Map<String, String> english) {
        assertThat(keys.stream().distinct().filter(key -> !english.containsKey(key)).toList())
                .as("Production text sources missing English resources").isEmpty();
        for (var key : keys)
            assertThat(english.get(key)).as(key).isNotBlank().doesNotContainPattern("[\\p{IsHan}]");
    }

    private static Set<String> arguments(String message) {
        return ARGUMENT.matcher(message).results().map(match -> match.group(1)).collect(Collectors.toSet());
    }

    private Map<String, String> messages() throws Exception {
        var result = new LinkedHashMap<String, String>();
        var urls = getClass().getClassLoader().getResources("META-INF/muyun/i18n/messages_en.properties");
        while (urls.hasMoreElements()) {
            var properties = new Properties();
            try (var reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String key : properties.stringPropertyNames()) result.put(key, properties.getProperty(key));
        }
        assertThat(result).isNotEmpty();
        return result;
    }

    private static List<SimpleJavaFileObject> productionSources() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        assertThat(root).as("Repository root").isNotNull();
        var sources = new ArrayList<SimpleJavaFileObject>();
        try (var modules = Files.list(root)) {
            for (var module : modules.filter(Files::isDirectory).toList()) {
                Path java = module.resolve("src/main/java");
                if (!Files.isDirectory(java)) continue;
                try (var files = Files.walk(java)) {
                    for (var file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList())
                        sources.add(source(file.toUri().toString(), Files.readString(file)));
                }
            }
        }
        assertThat(sources).isNotEmpty();
        return sources;
    }

    private static SimpleJavaFileObject source(String name, String content) {
        URI uri = name.startsWith("file:") ? URI.create(name) : URI.create("string:///" + name);
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        };
    }

    private record Declarations(List<String> enumTypes, List<String> textKeys, List<TextReference> texts) { }

    private static Declarations parse(List<SimpleJavaFileObject> sources) throws Exception {
        // Parse Java syntax rather than regex: comments, multiline calls and nested binary names matter.
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        var enums = new ArrayList<String>();
        var texts = new ArrayList<TextReference>();
        var keys = new ArrayList<String>();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, sources);
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    private final ArrayDeque<String> nesting = new ArrayDeque<>();
                    @Override public Void visitClass(ClassTree tree, Void unused) {
                        boolean named = !tree.getSimpleName().toString().isEmpty();
                        if (named) nesting.addLast(tree.getSimpleName().toString());
                        if (tree.getKind() == Tree.Kind.ENUM)
                            enums.add(unit.getPackageName() + "." + String.join("$", nesting));
                        super.visitClass(tree, unused);
                        if (named) nesting.removeLast();
                        return null;
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                        String method = tree.getMethodSelect().toString();
                        var args = tree.getArguments();
                        // Runtime-generated sources belong to their producer catalogs, not literal declarations.
                        if ((method.equals("TextReference.code") || method.equals(TextReference.class.getName() + ".code"))
                                && args.size() == 2 && args.get(0) instanceof LiteralTree key && key.getValue() instanceof String id) {
                            keys.add(id);
                            if (args.get(1) instanceof LiteralTree text && text.getValue() instanceof String original)
                                texts.add(TextReference.code(id, original));
                        }
                        return super.visitMethodInvocation(tree, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertThat(diagnostics.getDiagnostics().stream().filter(item -> item.getKind() == Diagnostic.Kind.ERROR).toList())
                .as("Production Java parse errors").isEmpty();
        return new Declarations(enums, keys, texts);
    }
}
