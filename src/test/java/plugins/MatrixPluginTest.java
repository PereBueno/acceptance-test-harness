package plugins;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import com.google.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jenkinsci.test.acceptance.Matcher;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.Since;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.po.LabelAxis;
import org.jenkinsci.test.acceptance.po.MatrixBuild;
import org.jenkinsci.test.acceptance.po.MatrixConfiguration;
import org.jenkinsci.test.acceptance.po.MatrixProject;
import org.jenkinsci.test.acceptance.po.MatrixRun;
import org.jenkinsci.test.acceptance.po.Slave;
import org.jenkinsci.test.acceptance.po.StringParameter;
import org.jenkinsci.test.acceptance.slave.SlaveProvider;
import org.junit.Before;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

@WithPlugins({"matrix-project"})
public class MatrixPluginTest extends AbstractJUnitTest {

    /* Groovy script to select which builds of the matrix will be generated.
     * For this test: Only when multiplying the value of the axis is an odd number would execute the build:
     *   [1,1]: Built
     *   [1,2]: Not built
     *   [1,3]: Built
     *   [2,1]: Not built
     *   [2,2]: Not built
     *   [2,3]: Not built
     *   [3,1]: Built
     *   [3,2]: Not built
     *   [3,3]: Built
     */
    private static final String GROOVY_SELECTOR_SCRIPT = "combinations.each{\n" + "   def x = it.axis_x as Integer\n"
            + "   def y = it.axis_y as Integer\n"
            + "   def i = (x * y) % 2\n"
            + "   if(i == 0) {\n"
            + "      return \n"
            + "   }\n"
            + "   result[it.axis_y] = result[it.axis_y] ?: []\n"
            + "   result[it.axis_y] << it\n"
            + "}\n"
            + " \n"
            + "[result, true]";
    private static final String STRATEGY = "Groovy Script Matrix Executor Strategy";
    private static final int AXIS_MAX_VALUE = 3;
    private static final int AXIS_X_TEST_NOT_BUILT = 1;
    private static final int AXIS_Y_TEST_NOT_BUILT = 2;
    private static final int AXIS_X_TEST_BUILT = 1;
    private static final int AXIS_Y_TEST_BUILT = 1;
    private static final int AXIS_INITIAL_VALUE = 1;
    private static final String AXIS_X = "axis_x";
    private static final String AXIS_Y = "axis_y";
    private static final String AXIS_X_VALUES = "1 2 3";
    private static final String AXIS_Y_VALUES = "1 2 3";

    MatrixProject job;

    @Inject
    SlaveProvider slaves;

    @Before
    public void setUp() {
        job = jenkins.jobs.create(MatrixProject.class);
    }

    @Test
    public void run_configurations_sequentially() {
        job.configure();
        job.addUserAxis("user_axis", "axis1 axis2 axis3");
        job.runSequentially.check();
        job.addShellStep("sleep 5");
        job.save();

        MatrixBuild b = job.startBuild().waitUntilStarted().as(MatrixBuild.class);
        assertThatBuildHasRunSequentially(b);
    }

    private void assertThatBuildHasRunSequentially(MatrixBuild b) {
        List<MatrixRun> builds = b.getConfigurations();

        while (b.isInProgress()) {
            int running = 0;
            for (MatrixRun r : builds) {
                if (r.isInProgress()) {
                    running++;
                }
            }

            assertThat("Too many configurations running at once", running, is(lessThan(2)));
            sleep(100);
        }
    }

    @Test
    public void run_a_matrix_job() {
        job.configure();
        job.addUserAxis("user_axis", "axis1 axis2 axis3");
        job.addShellStep("ls");
        job.save();
        job.startBuild().shouldSucceed();
        for (MatrixConfiguration c : job.getConfigurations()) {
            c.getLastBuild().shouldContainsConsoleOutput("\\+ ls");
        }
    }

    @Test
    public void run_touchstone_builds_first_with_result_stable() {
        job.configure();
        job.addUserAxis("user_axis", "axis1 axis2 axis3");
        job.addShellStep("false");
        job.setTouchStoneBuild("user_axis=='axis3'", "UNSTABLE");
        job.save();
        MatrixBuild b = job.startBuild().waitUntilFinished().as(MatrixBuild.class);

        b.getConfiguration("user_axis=axis1").shouldNotExist();
        b.getConfiguration("user_axis=axis2").shouldNotExist();
        b.getConfiguration("user_axis=axis3").shouldExist();
    }

    @Test
    public void run_build_with_combination_filter() {
        job.configure();
        job.addUserAxis("user_axis", "axis1 axis2 axis3");
        job.setCombinationFilter("user_axis=='axis2'");
        job.addShellStep("echo hello");
        job.save();

        MatrixBuild b = job.startBuild().waitUntilFinished().as(MatrixBuild.class);

        b.getConfiguration("user_axis=axis1").shouldNotExist();
        b.getConfiguration("user_axis=axis2").shouldExist();
        b.getConfiguration("user_axis=axis3").shouldNotExist();
    }

    @Test
    @Issue("JENKINS-7285")
    @Since("1.515")
    public void use_job_parameters_in_combination_filters() {
        job.configure();
        job.addUserAxis("run", "yes maybe no");
        job.setCombinationFilter("run=='yes' || (run=='maybe' && condition=='true')");
        job.addParameter(StringParameter.class).setName("condition");
        job.save();

        MatrixBuild b =
                job.startBuild(Map.of("condition", "false")).waitUntilFinished().as(MatrixBuild.class);
        b.getConfiguration("run=yes").shouldExist();
        b.getConfiguration("run=maybe").shouldNotExist();
        b.getConfiguration("run=no").shouldNotExist();

        b = job.startBuild(Map.of("condition", "true")).waitUntilFinished().as(MatrixBuild.class);
        b.getConfiguration("run=yes").shouldExist();
        b.getConfiguration("run=maybe").shouldExist();
        b.getConfiguration("run=no").shouldNotExist();
    }

    @Test
    public void run_configurations_on_with_a_given_label() throws Exception {
        Slave s = slaves.get().install(jenkins).get();
        s.configure();
        s.setLabels("label1");
        s.save();

        job.configure();
        LabelAxis a = job.addAxis(LabelAxis.class);
        String builtInNodeName = "built-in";
        String builtInNodeDescription = "the built-in node";
        a.select(builtInNodeName);
        a.select("label1");
        job.save();

        MatrixBuild b = job.startBuild().waitUntilFinished().as(MatrixBuild.class);
        b.getConfiguration("label=" + builtInNodeName)
                .shouldContainsConsoleOutput("(Building|Building remotely) on " + builtInNodeDescription);
        b.getConfiguration("label=label1")
                .shouldContainsConsoleOutput("(Building|Building remotely) on " + s.getName());
    }

    private void assertBuilt(MatrixBuild build, int axisX, int axisY) {
        for (int x = AXIS_INITIAL_VALUE; x <= AXIS_MAX_VALUE; x++) {
            for (int y = AXIS_INITIAL_VALUE; y <= AXIS_MAX_VALUE; y++) {
                // Only (x*y) odd combinations are valid
                if (isOdd(x * y)) {
                    if (x == axisX && y == axisY) {
                        assertThat(build.getConfiguration(AXIS_X + "=" + x + "," + AXIS_Y + "=" + y), built());
                    } else {
                        assertThat(build.getConfiguration(AXIS_X + "=" + x + "," + AXIS_Y + "=" + y), not(built()));
                    }
                }
            }
        }
    }

    private void assertExist(MatrixBuild build) {
        for (int x = AXIS_INITIAL_VALUE; x <= AXIS_MAX_VALUE; x++) {
            for (int y = AXIS_INITIAL_VALUE; y <= AXIS_MAX_VALUE; y++) {
                if (isOdd(x * y)) {
                    build.getConfiguration(AXIS_X + "=" + x + "," + AXIS_Y + "=" + y)
                            .shouldExist();
                } else {
                    build.getConfiguration(AXIS_X + "=" + x + "," + AXIS_Y + "=" + y)
                            .shouldNotExist();
                }
            }
        }
    }

    private MatrixBuild waitForSuccessBuild(MatrixProject job) {
        return (MatrixBuild) job.getLastBuild().waitUntilFinished().shouldSucceed();
    }

    private Matcher<? super MatrixRun> built() {
        return new Matcher<>("Matrix run exists") {
            @Override
            public boolean matchesSafely(MatrixRun item) {
                return item.exists();
            }
        };
    }

    private boolean isOdd(int i) {
        return (i % 2 != 0);
    }
}
