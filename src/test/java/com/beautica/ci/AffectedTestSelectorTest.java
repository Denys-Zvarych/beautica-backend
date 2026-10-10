package com.beautica.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AffectedTestSelectorTest {

    private static final String BOOKING_SERVICE = "com.beautica.booking.service.BookingService";
    private static final String BOOKING_CONTROLLER = "com.beautica.booking.controller.BookingController";

    private static Selection select(SourceTreeFixture tree, String nameStatus) {
        return select(tree, nameStatus, path -> Optional.empty());
    }

    private static Selection select(SourceTreeFixture tree, String nameStatus, Function<String, Optional<String>> deleted) {
        return new AffectedTestSelector(tree.graph(), deleted).select(ChangedPath.parseNameStatus(nameStatus));
    }

    private static String modified(String fqn) {
        return "M\t" + SourceTreeFixture.mainPath(fqn) + "\n";
    }

    private static SourceTreeFixture bookingTree() {
        return new SourceTreeFixture()
            .main(BOOKING_SERVICE)
            .main(BOOKING_CONTROLLER, List.of(BOOKING_SERVICE), "BookingService service;")
            .main("com.beautica.salon.service.SalonService")
            .test("com.beautica.booking.service.BookingServiceTest", List.of(), "", "BookingService s;")
            .test("com.beautica.booking.BookingFlowIT", List.of(BOOKING_SERVICE), "", "BookingService s;")
            .test("com.beautica.booking.controller.BookingControllerTest", List.of(BOOKING_CONTROLLER), "@WebMvcTest(BookingController.class)", "")
            .test("com.beautica.salon.service.SalonServiceTest", List.of(), "", "SalonService s;")
            .test("com.beautica.salon.SalonBlackBoxIT", List.of(), "@SpringBootTest", "")
            .pad(40);
    }

    @Test
    void should_selectDependentTestsOnly_when_bookingServiceChanges() {
        Selection result = select(bookingTree(), modified(BOOKING_SERVICE));

        assertThat(result.mode()).isEqualTo(Selection.Mode.SELECTIVE);
        assertThat(result.tests()).contains(
            "com.beautica.booking.service.BookingServiceTest",
            "com.beautica.booking.BookingFlowIT",
            "com.beautica.booking.controller.BookingControllerTest");
        assertThat(result.tests()).noneMatch(t -> t.contains(".salon.") || t.contains(".pad"));
    }

    @Test
    void should_selectSamePackageTestWithoutImport_when_itMentionsTheChangedClass() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.x.Foo")
            .main("com.beautica.x.Unrelated")
            .test("com.beautica.x.FooTest", List.of(), "", "Foo foo;")
            .test("com.beautica.x.UnrelatedTest", List.of(), "", "Unrelated u;")
            .pad(10);

        Selection result = select(tree, modified("com.beautica.x.Foo"));

        assertThat(result.tests()).containsExactly("com.beautica.x.FooTest");
    }

    @Test
    void should_notSelectSamePackageTest_when_itNeverNamesTheChangedClass() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.x.Foo")
            .test("com.beautica.x.BarTest")
            .pad(10);

        Selection result = select(tree, modified("com.beautica.x.Foo"));

        assertThat(result.tests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/wrapper/gradle-wrapper.properties",
        "gradlew.bat",
        "src/main/resources/application.yml",
        "src/main/resources/db/migration/V1__init.sql",
        "src/test/resources/application-test.yml",
        "src/main/java/com/beautica/config/CacheConfig.java",
        "src/main/java/com/beautica/common/security/JwtThing.java",
        "src/main/java/com/beautica/common/exception/SomeException.java",
        "src/main/java/com/beautica/BeauticaApplication.java",
        "src/test/java/com/beautica/TestConstants.java",
        "src/test/java/com/beautica/booking/AbstractStaffBookingIT.java",
        "src/test/java/com/beautica/support/HibernateStatistics.java",
        "src/test/java/com/beautica/booking/OverlapRaceSupport.java",
        "src/test/java/com/beautica/config/TestSecurityConfig.java",
        "src/test/java/com/beautica/ci/AffectedTestSelector.java",
        "scripts/ci/select-tests.sh",
        ".github/workflows/pr-validate.yml",
        "docker/local/docker-compose.yml",
    })
    void should_selectFull_when_alwaysFullPathChanges(String path) {
        Selection result = select(bookingTree(), "M\t" + path + "\n");

        assertThat(result.mode()).isEqualTo(Selection.Mode.FULL);
        assertThat(result.reason()).isEqualTo("always-full path: " + path);
    }

    /** One case per entry of {@code SelectorRules.FULL_CONTENT_REGEX_SOURCES}; each declaration trips ONLY its own rule. */
    private static Stream<Arguments> contentRuleCases() {
        return Stream.of(
            Arguments.of("@Configuration\\b", "@Configuration class A { }"),
            Arguments.of("@ControllerAdvice\\b", "@ControllerAdvice class A { }"),
            Arguments.of("@RestControllerAdvice\\b", "@RestControllerAdvice class A { }"),
            Arguments.of("@Aspect\\b", "@Aspect class A { }"),
            Arguments.of("\\bOncePerRequestFilter\\b", "class A extends OncePerRequestFilter { }"),
            Arguments.of("@EnableWebSecurity\\b", "@EnableWebSecurity class A { }"),
            Arguments.of("@ConfigurationProperties\\b", "@ConfigurationProperties(\"x\") class A { }"),
            Arguments.of("@EventListener\\b", "class A { @EventListener void on() { } }"),
            Arguments.of("@TransactionalEventListener\\b", "class A { @TransactionalEventListener void on() { } }"),
            Arguments.of("@Scheduled\\b", "class A { @Scheduled(cron = \"0 * * * * *\") void run() { } }"),
            Arguments.of("\\bimplements\\b[^{]*\\bWebMvcConfigurer\\b", "class A implements Filter, WebMvcConfigurer { }"),
            Arguments.of("\\bimplements\\b[^{]*\\bConverter\\b", "class A implements Converter<String, Integer> { }"),
            Arguments.of("\\bimplements\\b[^{]*\\bHandlerMethodArgumentResolver\\b", "class A implements HandlerMethodArgumentResolver { }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentRuleCases")
    void should_selectFull_when_changedMainFileHoldsSpringWiredMarker(String rule, String declaration) {
        SourceTreeFixture tree = bookingTree();
        tree.files().put(SourceTreeFixture.mainPath("com.beautica.booking.service.Wired"),
            "package com.beautica.booking.service;\n" + declaration + "\n");

        Selection result = select(tree, modified("com.beautica.booking.service.Wired"));

        assertThat(result.mode()).as(rule).isEqualTo(Selection.Mode.FULL);
        assertThat(result.reason()).isEqualTo("Spring-wired marker " + rule + " in "
            + SourceTreeFixture.mainPath("com.beautica.booking.service.Wired"));
    }

    @Test
    void should_haveContentRuleCase_when_aContentRuleExists() {
        List<String> covered = contentRuleCases().map(a -> (String) a.get()[0]).toList();

        assertThat(covered).containsAll(List.of(SelectorRules.FULL_CONTENT_REGEX_SOURCES));
    }

    @Test
    void should_notSelectFull_when_changedMainFileHoldsPlainService() {
        SourceTreeFixture tree = bookingTree();
        tree.files().put(SourceTreeFixture.mainPath("com.beautica.booking.service.Plain"),
            "package com.beautica.booking.service;\n@Service class Plain { @ConfigurationPropertiesScan void x() { } }\n");

        Selection result = select(tree, modified("com.beautica.booking.service.Plain"));

        assertThat(result.mode()).isNotEqualTo(Selection.Mode.FULL);
    }

    @Test
    void should_notSelectFull_when_markerAppearsOnlyInComments() {
        SourceTreeFixture tree = bookingTree();
        tree.files().put(SourceTreeFixture.mainPath("com.beautica.booking.domain.Rule"),
            "package com.beautica.booking.domain;\n/**\n * No {@code @Scheduled} job ever runs this.\n */\npublic class Rule {\n"
                + "    // the listener is @TransactionalEventListener(AFTER_COMMIT)\n    void x() { }\n}\n");

        Selection result = select(tree, modified("com.beautica.booking.domain.Rule"));

        assertThat(result.mode()).isNotEqualTo(Selection.Mode.FULL);
    }

    @Test
    void should_treatMarkerAfterACommentLikeBareMarker_when_fileHasLeadingComment() {
        String job = "com.beautica.booking.domain.Job";
        String declaration = "public class Job {\n    @Scheduled(cron = \"0 0 * * * *\")\n    void run() { }\n}\n";
        SourceTreeFixture bare = bookingTree();
        bare.files().put(SourceTreeFixture.mainPath(job), "package com.beautica.booking.domain;\n" + declaration);
        SourceTreeFixture commented = bookingTree();
        commented.files().put(SourceTreeFixture.mainPath(job),
            "package com.beautica.booking.domain;\n/** Runs nightly. */\n" + declaration);

        Selection withComment = select(commented, modified(job));

        assertThat(withComment).isEqualTo(select(bare, modified(job)));
    }

    @Test
    void should_selectControllerSlice_when_controllerGainsNewDependency() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.notification.NotificationService")
            .main(BOOKING_CONTROLLER, List.of("com.beautica.notification.NotificationService"), "NotificationService n;")
            .test("com.beautica.booking.controller.BookingControllerTest", List.of(BOOKING_CONTROLLER),
                "@WebMvcTest(controllers = BookingController.class)", "")
            .pad(20);

        Selection result = select(tree, modified(BOOKING_CONTROLLER));

        assertThat(result.tests()).containsExactly("com.beautica.booking.controller.BookingControllerTest");
    }

    @Test
    void should_selectSliceByAnnotationTarget_when_testNeverImportsTheController() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main(BOOKING_CONTROLLER)
            .test("com.beautica.web.SliceOnlyTest", List.of(), "@WebMvcTest(BookingController.class)", "")
            .pad(20);

        Selection result = select(tree, modified(BOOKING_CONTROLLER));

        assertThat(result.tests()).contains("com.beautica.web.SliceOnlyTest");
    }

    @Test
    void should_selectBareWebMvcSlice_when_anyControllerChanges() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.booking.controller.BookingController")
            .test("com.beautica.web.AllControllersTest", List.of(), "@WebMvcTest", "")
            .pad(20);
        tree.files().put(SourceTreeFixture.mainPath("com.beautica.booking.controller.BookingController"),
            "package com.beautica.booking.controller;\n@RestController public class BookingController { }\n");

        Selection result = select(tree, modified("com.beautica.booking.controller.BookingController"));

        assertThat(result.tests()).contains("com.beautica.web.AllControllersTest");
    }

    @Test
    void should_selectFull_when_abstractTestBaseChanges() {
        Selection result = select(bookingTree(), "M\tsrc/test/java/com/beautica/booking/AbstractFooIT.java\n");

        assertThat(result.mode()).isEqualTo(Selection.Mode.FULL);
    }

    @Test
    void should_neverEmitAbstractClasses_when_subclassIsAffected() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.x.Foo")
            .abstractTest("com.beautica.x.FooBaseIT", List.of(), "Foo foo;")
            .test("com.beautica.x.FooIT", List.of(), "", "FooBaseIT base;")
            .pad(20);

        Selection result = select(tree, modified("com.beautica.x.Foo"));

        assertThat(result.tests()).containsExactly("com.beautica.x.FooIT");
        assertThat(new AffectedTestSelector(tree.graph(), p -> Optional.empty()).allTestClasses())
            .doesNotContain("com.beautica.x.FooBaseIT");
    }

    @Test
    void should_selectChangedTestClass_when_onlyATestFileChanges() {
        SourceTreeFixture tree = bookingTree();

        Selection result = select(tree, "M\t" + SourceTreeFixture.testPath("com.beautica.salon.service.SalonServiceTest") + "\n");

        assertThat(result.tests()).containsExactly("com.beautica.salon.service.SalonServiceTest");
    }

    @Test
    void should_selectImporters_when_sharedFixtureChanges() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .test("com.beautica.x.XFixtures", List.of(), "", "")
            .test("com.beautica.y.UsesFixturesTest", List.of("com.beautica.x.XFixtures"), "", "XFixtures f;")
            .pad(20);
        tree.files().put(SourceTreeFixture.testPath("com.beautica.x.XFixtures"),
            "package com.beautica.x;\npublic class XFixtures { }\n");

        Selection result = select(tree, "M\t" + SourceTreeFixture.testPath("com.beautica.x.XFixtures") + "\n");

        assertThat(result.tests()).containsExactly("com.beautica.y.UsesFixturesTest");
    }

    @Test
    void should_selectNone_when_onlyDocsChange() {
        Selection result = select(bookingTree(), "M\tdocs/x.md\nA\tREADME.md\nM\t.gitignore\nM\t.gitleaks.toml\nM\tdocs/a/b.txt\n");

        assertThat(result.mode()).isEqualTo(Selection.Mode.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo/bar.txt", "Dockerfile", "scripts/forbid_th_utext_in_email.sh", "src/main/java/com/beautica/x/notes.txt"})
    void should_selectFull_when_pathIsUnknown(String path) {
        Selection result = select(bookingTree(), "M\t" + path + "\n");

        assertThat(result.mode()).isEqualTo(Selection.Mode.FULL);
        assertThat(result.reason()).contains("unknown path");
    }

    @Test
    void should_selectFull_when_selectionExceedsFortyPercent() {
        SourceTreeFixture tree = tenTestsWhereFirstNDependOnFoo(5);

        Selection result = select(tree, modified("com.beautica.x.Foo"));

        assertThat(result.mode()).isEqualTo(Selection.Mode.FULL);
    }

    @Test
    void should_stayStaySelective_when_selectionIsExactlyFortyPercent() {
        SourceTreeFixture tree = tenTestsWhereFirstNDependOnFoo(4);

        Selection result = select(tree, modified("com.beautica.x.Foo"));

        assertThat(result.mode()).isEqualTo(Selection.Mode.SELECTIVE);
        assertThat(result.tests()).hasSize(4);
    }

    private static SourceTreeFixture tenTestsWhereFirstNDependOnFoo(int n) {
        SourceTreeFixture tree = new SourceTreeFixture().main("com.beautica.x.Foo");
        for (int i = 0; i < 10; i++) {
            boolean uses = i < n;
            tree.test("com.beautica.t" + i + ".T" + i + "Test",
                uses ? List.of("com.beautica.x.Foo") : List.of(), "", uses ? "Foo f;" : "");
        }
        return tree;
    }

    @Test
    void should_seedWholePackage_when_mainFileIsDeleted() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .test("com.beautica.gone.NeighbourTest")
            .test("com.beautica.other.OtherTest")
            .pad(20);

        Selection result = select(tree, "D\t" + SourceTreeFixture.mainPath("com.beautica.gone.Removed") + "\n",
            path -> Optional.of("class Removed { }"));

        assertThat(result.tests()).containsExactly("com.beautica.gone.NeighbourTest");
    }

    @Test
    void should_seedOldAndNewPackage_when_fileIsRenamed() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.newpkg.Moved")
            .test("com.beautica.oldpkg.OldNeighbourTest")
            .test("com.beautica.newpkg.NewNeighbourTest", List.of(), "", "Moved m;")
            .test("com.beautica.other.OtherTest")
            .pad(20);
        String status = "R100\t" + SourceTreeFixture.mainPath("com.beautica.oldpkg.Moved") + "\t"
            + SourceTreeFixture.mainPath("com.beautica.newpkg.Moved") + "\n";

        Selection result = select(tree, status, path -> Optional.of("class Moved { }"));

        assertThat(result.tests()).containsExactlyInAnyOrder(
            "com.beautica.oldpkg.OldNeighbourTest", "com.beautica.newpkg.NewNeighbourTest");
    }

    @Test
    void should_treatDeletedFileLikeLiveFile_when_itHeldSpringWiredMarker() {
        String body = "package com.beautica.booking;\nclass Job { @Scheduled void run() { } }";
        String path = SourceTreeFixture.mainPath("com.beautica.booking.Job");
        SourceTreeFixture live = bookingTree();
        live.files().put(path, body);

        Selection deleted = select(bookingTree(), "D\t" + path + "\n", p -> Optional.of(body));

        assertThat(deleted.mode()).isEqualTo(select(live, "M\t" + path + "\n").mode());
    }

    @Test
    void should_notSelectFull_when_deletedMainFileMentionsMarkerOnlyInComments() {
        SourceTreeFixture tree = bookingTree();

        Selection result = select(tree, "D\t" + SourceTreeFixture.mainPath("com.beautica.booking.Old") + "\n",
            path -> Optional.of("/** No @Scheduled sweep exists. */\nclass Old { }"));

        assertThat(result.mode()).isNotEqualTo(Selection.Mode.FULL);
    }

    @Test
    void should_selectFull_when_deletedMainFileContentIsUnavailable() {
        Selection result = select(bookingTree(), "D\t" + SourceTreeFixture.mainPath("com.beautica.booking.Job") + "\n");

        assertThat(result.mode()).isEqualTo(Selection.Mode.FULL);
        assertThat(result.reason()).contains("unreadable");
    }

    @Test
    void should_resolveWildcardStaticAndNestedImports_when_buildingGraph() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.util.Strings")
            .main("com.beautica.wild.pkg.WildA")
            .main("com.beautica.nest.Outer")
            .test("com.beautica.a.StaticImportTest", List.of("static com.beautica.util.Strings.slug"), "", "")
            .test("com.beautica.b.WildcardTest", List.of("com.beautica.wild.pkg.*"), "", "")
            .test("com.beautica.c.NestedTest", List.of("com.beautica.nest.Outer.Inner"), "", "")
            .test("com.beautica.d.ForeignTest", List.of("org.junit.jupiter.api.Assertions.*"), "", "")
            .pad(30);

        assertThat(select(tree, modified("com.beautica.util.Strings")).tests()).containsExactly("com.beautica.a.StaticImportTest");
        assertThat(select(tree, modified("com.beautica.wild.pkg.WildA")).tests()).containsExactly("com.beautica.b.WildcardTest");
        assertThat(select(tree, modified("com.beautica.nest.Outer")).tests()).containsExactly("com.beautica.c.NestedTest");
    }

    @Test
    void should_stopAtTwoHops_when_dependencyChainIsLonger() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.a.A")
            .main("com.beautica.b.B", List.of("com.beautica.a.A"), "A a;")
            .main("com.beautica.c.C", List.of("com.beautica.b.B"), "B b;")
            .test("com.beautica.t1.OneHopTest", List.of("com.beautica.a.A"), "", "A a;")
            .test("com.beautica.t2.TwoHopTest", List.of("com.beautica.b.B"), "", "B b;")
            .test("com.beautica.t3.ThreeHopTest", List.of("com.beautica.c.C"), "", "C c;")
            .pad(30);

        Selection result = select(tree, modified("com.beautica.a.A"));

        assertThat(result.tests()).containsExactlyInAnyOrder("com.beautica.t1.OneHopTest", "com.beautica.t2.TwoHopTest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"@SpringBootTest", "@DataJpaTest", "@AutoConfigureMockMvc"})
    void should_selectBlackBoxContextTestOfSameFeature_when_featureSourceChanges(String annotation) {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.review.service.ReviewService")
            .test("com.beautica.review.ReviewBlackBoxIT", List.of(), annotation, "")
            .test("com.beautica.salon.SalonBlackBoxIT", List.of(), annotation, "")
            .test("com.beautica.review.PlainUnitTest")
            .pad(30);

        Selection result = select(tree, modified("com.beautica.review.service.ReviewService"));

        assertThat(result.tests()).containsExactly("com.beautica.review.ReviewBlackBoxIT");
    }

    @Test
    void should_selectIntegrationSubclassOfAbstractBase_when_featureSourceChanges() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .main("com.beautica.review.service.ReviewService")
            .test("com.beautica.review.ViaBaseIT", List.of(), "", "")
            .pad(30);
        tree.files().put(SourceTreeFixture.testPath("com.beautica.review.ViaBaseIT"),
            "package com.beautica.review;\npublic class ViaBaseIT extends AbstractIntegrationTest { @Test void t() { } }\n");

        Selection result = select(tree, modified("com.beautica.review.service.ReviewService"));

        assertThat(result.tests()).containsExactly("com.beautica.review.ViaBaseIT");
    }

    @Test
    void should_selectEmptyList_when_changedSourceIsReachedByNoTest() {
        SourceTreeFixture tree = new SourceTreeFixture().main("com.beautica.lonely.Lonely").pad(5);

        Selection result = select(tree, modified("com.beautica.lonely.Lonely"));

        assertThat(result.mode()).isEqualTo(Selection.Mode.SELECTIVE);
        assertThat(result.tests()).isEmpty();
    }

    @Test
    void should_countNestedOnlyAndAnnotatedClassesAsTests_when_enumeratingAll() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .test("com.beautica.x.LooksLikeHelperFixtures")
            .test("com.beautica.x.PlainTest")
            .main("com.beautica.x.NotATestTest");

        List<String> all = new AffectedTestSelector(tree.graph(), p -> Optional.empty()).allTestClasses();

        assertThat(all).containsExactly("com.beautica.x.LooksLikeHelperFixtures", "com.beautica.x.PlainTest");
    }

    @Test
    void should_notCountHelperAsTest_when_itOnlyMentionsAnnotationInsideAString() {
        SourceTreeFixture tree = new SourceTreeFixture();
        tree.files().put(SourceTreeFixture.testPath("com.beautica.x.CodeGenerator"),
            "package com.beautica.x;\npublic class CodeGenerator { String s = \"@Test void t() { }\"; }\n");

        List<String> all = new AffectedTestSelector(tree.graph(), p -> Optional.empty()).allTestClasses();

        assertThat(all).isEmpty();
    }

    @Test
    void should_parseRenameAsDeletedOldPlusLiveNew_when_readingNameStatus() {
        List<ChangedPath> changes = ChangedPath.parseNameStatus("R087\told/A.java\tnew/A.java\nM\tb.txt\nD\tc.txt\n\n");

        assertThat(changes).containsExactly(
            new ChangedPath("old/A.java", true), new ChangedPath("new/A.java", false),
            new ChangedPath("b.txt", false), new ChangedPath("c.txt", true));
    }

    @Test
    void should_scanRealFilesAndEmitSelection_when_runThroughCli(@TempDir Path root) throws IOException {
        write(root, "src/main/java/com/beautica/x/Foo.java", "package com.beautica.x;\npublic class Foo { }\n");
        write(root, "src/test/java/com/beautica/x/FooTest.java",
            "package com.beautica.x;\nimport org.junit.jupiter.api.Test;\nclass FooTest { @Test void t() { Foo f; } }\n");
        for (int i = 0; i < 4; i++) {
            write(root, "src/test/java/com/beautica/p" + i + "/P" + i + "Test.java",
                "package com.beautica.p" + i + ";\nclass P" + i + "Test { @Test void t() { } }\n");
        }
        Path changes = write(root, "changes.txt", "M\tsrc/main/java/com/beautica/x/Foo.java\n");
        Path out = root.resolve("out/selected.txt");

        String stdout = runCli("--root", root.toString(), "--changes", changes.toString(), "--out", out.toString());

        assertThat(stdout).contains("MODE=selective", "COUNT=1", "TOTAL=5");
        assertThat(Files.readAllLines(out)).containsExactly("com.beautica.x.FooTest");
    }

    @Test
    void should_writeOnlyRequestedShard_when_cliGetsAllAndShard(@TempDir Path root) throws IOException {
        for (int i = 0; i < 6; i++) {
            write(root, "src/test/java/com/beautica/p" + i + "/P" + i + "Test.java",
                "package com.beautica.p" + i + ";\nclass P" + i + "Test { @Test void t() { } }\n");
        }
        Path out = root.resolve("shard.txt");

        runCli("--root", root.toString(), "--all", "--shard", "1/3", "--out", out.toString());

        assertThat(Files.readAllLines(out)).containsExactly("com.beautica.p1.P1Test", "com.beautica.p4.P4Test");
    }

    @Test
    void should_weighHeavyAboveSliceAboveLight_when_classifyingTests() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .test("com.beautica.x.PlainTest")
            .test("com.beautica.x.ByNameIT")
            .test("com.beautica.x.CtxTest", List.of(), "@SpringBootTest", "")
            .test("com.beautica.x.SliceTest", List.of(), "@WebMvcTest(Foo.class)", "")
            .test("com.beautica.x.ViaBaseTest", List.of(), "", "") ;
        tree.files().put(SourceTreeFixture.testPath("com.beautica.x.SubTest"),
            "package com.beautica.x;\nclass SubTest extends AbstractFooBase { @Test void t() { } }\n");
        AffectedTestSelector selector = new AffectedTestSelector(tree.graph(), p -> Optional.empty());

        assertThat(selector.weightOf("com.beautica.x.PlainTest")).isEqualTo(SelectorRules.LIGHT_TEST_WEIGHT);
        assertThat(selector.weightOf("com.beautica.x.ByNameIT")).isEqualTo(SelectorRules.HEAVY_TEST_WEIGHT);
        assertThat(selector.weightOf("com.beautica.x.CtxTest")).isEqualTo(SelectorRules.HEAVY_TEST_WEIGHT);
        assertThat(selector.weightOf("com.beautica.x.SubTest")).isEqualTo(SelectorRules.HEAVY_TEST_WEIGHT);
        assertThat(selector.weightOf("com.beautica.x.SliceTest")).isEqualTo(SelectorRules.SLICE_TEST_WEIGHT);
        assertThat(selector.weightOf("com.beautica.unknown.Nope")).isEqualTo(SelectorRules.LIGHT_TEST_WEIGHT);
    }

    @Test
    void should_balanceByWeightAndDropExcludedPrefix_when_cliWritesShards(@TempDir Path root) throws IOException {
        for (int i = 0; i < 4; i++) {
            write(root, "src/test/java/com/beautica/s" + i + "/S" + i + "IT.java",
                "package com.beautica.s" + i + ";\n@SpringBootTest class S" + i + "IT { @Test void t() { } }\n");
        }
        for (int i = 0; i < 4; i++) {
            write(root, "src/test/java/com/beautica/ci/C" + i + "Test.java",
                "package com.beautica.ci;\nclass C" + i + "Test { @Test void t() { } }\n");
        }
        Path outDir = root.resolve("out");

        String stdout = runCli("--root", root.toString(), "--all", "--exclude", "com.beautica.ci.", "--shards", "2", "--out-dir", outDir.toString());

        assertThat(stdout).contains("COUNT=4", "SHARD_WEIGHTS=20,20");
        assertThat(Files.readAllLines(outDir.resolve("selected-tests.txt"))).hasSize(4).noneMatch(f -> f.startsWith("com.beautica.ci."));
        assertThat(Files.readAllLines(outDir.resolve("shard-0.txt"))).containsExactly("com.beautica.s0.S0IT", "com.beautica.s2.S2IT");
        assertThat(Files.readAllLines(outDir.resolve("shard-1.txt"))).containsExactly("com.beautica.s1.S1IT", "com.beautica.s3.S3IT");
    }

    @Test
    void should_rejectOutOfRangeShard_when_cliGetsBadShard() {
        assertThatThrownBy(() -> runCli("--root", ".", "--all", "--shard", "3/3"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String NESTED_BASE = "com.beautica.nest.AbstractShapeIT";

    /** Abstract base declaring @Nested; Legacy has no direct test, Visit has one; Plain extends a base WITHOUT @Nested. */
    private static SourceTreeFixture nestedFamilyTree() {
        SourceTreeFixture tree = new SourceTreeFixture()
            .abstractTest(NESTED_BASE, List.of(), "@Nested\nclass Reads { @Test void r() { } }")
            .abstractTest("com.beautica.nest.AbstractPlainIT", List.of(), "")
            .subclassTest("com.beautica.nest.LegacyShapeIT", List.of(), "extends AbstractShapeIT", "")
            .subclassTest("com.beautica.nest.VisitShapeIT", List.of(), "extends AbstractShapeIT", "@Test void own() { }")
            .subclassTest("com.beautica.nest.PlainIT", List.of(), "extends AbstractPlainIT", "@Test void own() { }");
        tree.files().put(SourceTreeFixture.testPath("com.beautica.other.MidIT"),
            "package com.beautica.other;\nimport " + NESTED_BASE + ";\nabstract class MidIT extends AbstractShapeIT { }\n");
        tree.subclassTest("com.beautica.other.DeepIT", List.of(), "extends MidIT", "");
        return tree;
    }

    @Test
    void should_emitAliasToNestedDeclaringBase_when_subclassHasNoDirectTests() {
        AffectedTestSelector selector = new AffectedTestSelector(nestedFamilyTree().graph(), p -> Optional.empty());

        List<String> aliases = selector.reportAliases(selector.allTestClasses());

        assertThat(aliases).containsExactly(
            "com.beautica.nest.LegacyShapeIT\t" + NESTED_BASE,
            "com.beautica.nest.VisitShapeIT\t" + NESTED_BASE,
            "com.beautica.other.DeepIT\t" + NESTED_BASE);
    }

    @Test
    void should_emitAliasThroughIntermediateAbstractClass_when_importedBaseIsTwoLevelsUp() {
        SourceTreeFixture tree = nestedFamilyTree();
        tree.files().put(SourceTreeFixture.testPath("com.beautica.other.DeepIT"),
            "package com.beautica.other;\nclass DeepIT extends MidIT { }\n");
        AffectedTestSelector selector = new AffectedTestSelector(tree.graph(), p -> Optional.empty());

        assertThat(selector.reportAliases(List.of("com.beautica.other.DeepIT")))
            .containsExactly("com.beautica.other.DeepIT\t" + NESTED_BASE);
    }

    @Test
    void should_writeAliasSidecarAndKeepFamilyInOneShard_when_cliGetsOutDir(@TempDir Path root) throws IOException {
        nestedFamilyTree().files().forEach((path, body) -> {
            try {
                write(root, path, body);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Path outDir = root.resolve("out");

        runCli("--root", root.toString(), "--all", "--shards", "3", "--out-dir", outDir.toString());

        assertThat(Files.readAllLines(outDir.resolve("report-aliases.txt"))).contains(
            "com.beautica.nest.LegacyShapeIT\t" + NESTED_BASE, "com.beautica.nest.VisitShapeIT\t" + NESTED_BASE);
        for (int i = 0; i < 3; i++) {
            List<String> shard = Files.readAllLines(outDir.resolve("shard-" + i + ".txt"));
            assertThat(shard.contains("com.beautica.nest.LegacyShapeIT")).isEqualTo(shard.contains("com.beautica.nest.VisitShapeIT"));
            assertThat(shard.contains("com.beautica.nest.LegacyShapeIT")).isEqualTo(shard.contains("com.beautica.other.DeepIT"));
        }
    }

    @Test
    void should_writeEmptyAliasFile_when_noClassInheritsNested(@TempDir Path root) throws IOException {
        write(root, SourceTreeFixture.testPath("com.beautica.a.ATest"), "package com.beautica.a;\nclass ATest { @Test void t() { } }\n");
        Path outDir = root.resolve("out");

        runCli("--root", root.toString(), "--all", "--shards", "1", "--out-dir", outDir.toString());

        assertThat(Files.readAllLines(outDir.resolve("report-aliases.txt"))).isEmpty();
    }

    private static Path write(Path root, String rel, String body) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, body);
    }

    private static String runCli(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            AffectedTestSelector.main(args);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            System.setOut(original);
        }
        return captured.toString();
    }
}
