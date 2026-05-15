import java.net.URL;
import java.net.URLClassLoader;
import java.io.File;

public class ClassChecker {
    public static void main(String[] args) throws Exception {
        File file = new File("target/Recon-1.6-SNAPSHOT.jar");
        URL url = file.toURI().toURL();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{url}, ClassChecker.class.getClassLoader())) {
            try {
                Class.forName("net.enabify.recon.Recon", true, cl);
                System.out.println("Class loaded successfully!");
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}