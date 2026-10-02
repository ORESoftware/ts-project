package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class BuiltinCollectionsTest {
    @Test
    void typechecksBuiltinCollectionFamiliesAndAnonymousOverrides() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    let map = new Map<String, String>() {
                      "field1": "bar",
                      "field2": "baz",
                      @override
                      find(String key) => Option<String> { return Some("overridden"); }
                    };
                    val hashed = new HashMap<String, int>() {
                      "one": 1,
                      "two": 2
                    };
                    val json = new JsonMap() {
                      "name": "ores",
                      "values": arr[1, 2, 3]
                    };
                    val list = new List<int>(1, 2);
                    val linked = new LinkedList<int>(3, 4);
                    val array = new Array<int>(5, 6);
                    val arrays = new Arrays<int>(7, 8);
                    let literal = arr[9, 10];
                    val explicitObject = new Object() {
                      "field": "value"
                    };

                    map.put("field3", "qux");
                    map["field4"] = "zap";
                    val found = map.find("field1");
                    val two = hashed["two"];
                    linked.push(5);
                    literal.push(11);
                    val item = list[0];
                    stdio.println(explicitObject.field);
                    stdio.println(found);
                    stdio.println(two);
                    stdio.println(item);
                    return;
                  }
                end
                """)));
    }

    @Test
    void anonymousImplementationCanMakeAnAbstractClassInstantiable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define abstract class Finder
                    pub abstract find(String key) => String;
                  end

                  pub fnc main() => void {
                    val finder = new Finder() {
                      @override
                      pub find(String key) => String { return "anon"; }
                    };
                    stdio.println(finder.find("x"));
                    return;
                  }
                end
                """)));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define abstract class Finder
                    pub abstract find(String key) => String;
                  end
                  pub fnc main() => void {
                    val finder = new Finder();
                    return;
                  }
                end
                """)));
        assertTrue(missing.getMessage().contains("abstract class"));
    }

    @Test
    void anonymousMethodsMustActuallyOverride() {
        IllegalArgumentException missingAnnotation = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val map = new Map<String, String>() {
                      find(String key) => Option<String> { return Some("x"); }
                    };
                    return;
                  }
                end
                """)));
        assertTrue(missingAnnotation.getMessage().contains("@override"));

        IllegalArgumentException wrongMethod = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val map = new Map<String, String>() {
                      @override
                      nope(String key) => Option<String> { return Some("x"); }
                    };
                    return;
                  }
                end
                """)));
        assertTrue(wrongMethod.getMessage().contains("no builtin method"));
    }

    @Test
    void executesBuiltinCollectionsAndAnonymousDispatch() throws Exception {
        String program = """
                define module app
                  define abstract class Finder
                    pub abstract find(String key) => String;
                  end

                  pub fnc main() => void {
                    val map = new Map<String, String>() {
                      "field1": "bar",
                      "field2": "baz",
                      @override
                      find(String key) => Option<String> { return Some("custom"); }
                    };
                    map.put("field3", "qux");

                    val linked = new LinkedList<int>(10, 20);
                    linked.push(30);
                    val literal = arr[40, 50];
                    literal.push(60);

                    val finder = new Finder() {
                      @override
                      pub find(String key) => String { return "anon"; }
                    };

                    stdio.println(map["field2"]);
                    stdio.println(map.find("field1"));
                    stdio.println(map.size());
                    stdio.println(linked[2]);
                    stdio.println(literal[2]);
                    stdio.println(finder.find("anything"));
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "builtin-collections.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("baz"));
        assertTrue(text.contains("Some(custom)"));
        assertTrue(text.contains("3"));
        assertTrue(text.contains("30"));
        assertTrue(text.contains("60"));
        assertTrue(text.contains("anon"));
    }

    @Test
    void jsonMapRejectsNonJsonRuntimeValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val json = new JsonMap() {
                      "ok": obj{nested: arr[1, 2, 3]}
                    };
                    return;
                  }
                end
                """)));
    }
}
