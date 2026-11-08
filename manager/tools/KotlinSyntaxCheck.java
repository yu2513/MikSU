import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtPsiFactory;

class KotlinSyntaxCheck {
    public static void main(String[] args) throws Exception {
        var disposable = Disposer.newDisposable();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(
                disposable, new CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES
            );
            var factory = new KtPsiFactory(environment.getProject(), false);
            var root = Path.of(args[0]);
            List<Path> sources = new ArrayList<>();
            for (String relative : List.of(
                "app/src/main/java/io/milksu/manager/data/system",
                "app/src/main/java/io/milksu/manager/ui/screen/kpm"
            )) {
                try (var paths = Files.walk(root.resolve(relative))) {
                    paths.filter(p -> p.toString().endsWith(".kt")).forEach(sources::add);
                }
            }
            for (String relative : List.of(
                "app/src/main/java/io/milksu/manager/ui/viewmodel/KpmViewModel.kt",
                "app/src/main/java/io/milksu/manager/ui/viewmodel/SettingsViewModel.kt",
                "app/src/main/java/io/milksu/manager/ui/viewmodel/HomeViewModel.kt",
                "app/src/main/java/io/milksu/manager/ui/util/CoreManagerUpdater.kt",
                "app/src/main/java/io/milksu/manager/ui/viewmodel/MainActivityViewModel.kt",
                "app/src/main/java/io/milksu/manager/ui/MainActivity.kt",
                "app/src/main/java/io/milksu/manager/ui/navigation3/Routes.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/settings/SettingsMaterial.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/settings/SettingsMiuix.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/settings/SettingsScreen.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/home/HomeScreen.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/home/HomeMaterial.kt",
                "app/src/main/java/io/milksu/manager/ui/screen/home/HomeMiuix.kt",
                "app/build.gradle.kts", "build.gradle.kts"
            )) {
                sources.add(root.resolve(relative));
            }
            int errors = 0;
            for (Path source : sources) {
                String text = Files.readString(source).replace("\r\n", "\n").replace('\r', '\n');
                var file = factory.createFile(source.getFileName().toString(), text);
                int reported = 0;
                for (var error : PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class)) {
                    if (reported++ < 5) {
                        long line = text.substring(0, error.getTextOffset()).chars().filter(c -> c == '\n').count() + 1;
                        System.err.println(root.relativize(source) + ":" + line + ": " + error.getErrorDescription());
                    }
                    errors++;
                }
            }
            if (errors > 0) throw new IllegalStateException(errors + " Kotlin syntax errors");
            System.out.println("Kotlin syntax: " + sources.size() + " files passed (not an Android type check)");
        } finally {
            Disposer.dispose(disposable);
        }
    }
}
