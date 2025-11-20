import org.junit.jupiter.api.Test;
import com.palantir.gradle.plugintesting.GradlePluginTests;
import com.palantir.gradle.plugintesting.files.RootProject;
import com.palantir.gradle.plugintesting.files.SubProject;
import com.palantir.gradle.plugintesting.invoker.GradleInvoker;
import com.palantir.gradle.plugintesting.invoker.InvocationResult;
import com.palantir.gradle.plugintesting.assertj.InvocationResultAssert;
import com.palantir.gradle.plugintesting.assertj.TaskResultAssert;
import java.lang.reflect.Method;
import java.util.Arrays;

public class DebugTest {
    @Test
    void test() {
        System.out.println("=== Available Classes ===");
        System.out.println("GradlePluginTests: " + GradlePluginTests.class.getName());
        System.out.println("RootProject: " + RootProject.class.getName());
        System.out.println("SubProject: " + SubProject.class.getName());
        System.out.println("GradleInvoker: " + GradleInvoker.class.getName());
        System.out.println("InvocationResult: " + InvocationResult.class.getName());
        System.out.println("InvocationResultAssert: " + InvocationResultAssert.class.getName());
        System.out.println("TaskResultAssert: " + TaskResultAssert.class.getName());

        System.out.println("\n=== GradleInvoker methods ===");
        for (Method m : GradleInvoker.class.getDeclaredMethods()) {
            System.out.println("  " + m.getName() + ": " + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName());
        }

        System.out.println("\n=== InvocationResultAssert methods ===");
        for (Method m : InvocationResultAssert.class.getDeclaredMethods()) {
            System.out.println("  " + m.getName() + ": " + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName());
        }

        System.out.println("\n=== TaskResultAssert methods ===");
        for (Method m : TaskResultAssert.class.getDeclaredMethods()) {
            System.out.println("  " + m.getName() + ": " + Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName());
        }
    }
}
