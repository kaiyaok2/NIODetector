package edu.illinois.NIODetector.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom Maven plugin Mojo for rerunning tests.
 */
@Mojo(name = "rerun", requiresDependencyResolution = ResolutionScope.TEST)
public class RerunMojo extends AbstractMojo {

    /**
     * Comma-separated list of test classes and methods to rerun.
     */
    @Parameter(property = "test", defaultValue = "")
    private String test;

    /**
     * Reference to the current Maven project we're rerunning tests on
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Reference to the artifacts needed by NIODetector
     */
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    /**
     * Number of reruns for failing tests.
     */
    @Parameter(property = "numReruns", defaultValue = "3")
    private int numReruns;


    /**
     * Executes the Mojo to rerun tests.
     *
     * @throws MojoExecutionException if an error occurs during execution
     */
    public void execute() throws MojoExecutionException {

        List<String> testClassNames = new ArrayList<>();
        if (!(test == null) && !test.isEmpty()) {
            // Parse the test parameter and extract class and method names
            String[] tests = test.split(",");
            for (String testEntry : tests) {
                testClassNames.add(testEntry.trim());
            }
        } else {
            // If test parameter is not provided, find all test classes
            testClassNames = findTestClasses(project.getBuild().getTestOutputDirectory());
        }
    
        URLClassLoader classLoader = null;
        try {
            // Convert the paths to URLs
            URL testClassURL = new File(project.getBuild().getTestOutputDirectory()).toURI().toURL();

            List<URL> allURLs = new ArrayList<>();
            try {
                // Get all dependencies needed for NIODetector plugin
                List<URL> pluginDependencies = new ArrayList<>();
                for (Artifact artifact : pluginArtifacts) {
                    if (artifact.getFile() != null) {
                        pluginDependencies.add(artifact.getFile().toURI().toURL());
                    }
                }
                allURLs.addAll(pluginDependencies);

                // Get all test dependencies for current project
                List<String> projectTestDependenciesElements = project.getTestClasspathElements();
                List<URL> projectTestDependencies = new ArrayList<>();
                for (String dependency : projectTestDependenciesElements) {
                    projectTestDependencies.add(new File(dependency).toURI().toURL());
                }
                allURLs.addAll(projectTestDependencies);

                // Add system dependencies of current projec
                List<String> projectSystemDependenciesElements = project.getSystemClasspathElements();
                List<URL> projectSystemDependencies = new ArrayList<>();
                for (String dependency : projectSystemDependenciesElements) {
                    projectSystemDependencies.add(new File(dependency).toURI().toURL());
                }
                allURLs.addAll(projectSystemDependencies);
            } catch (Exception e) {
                throw new MojoExecutionException("Error retrieving all dependent Jars", e);
            }
            // Add test classes found in current project
            allURLs.add(testClassURL);

            // Create IsolatedURLClassLoader with all relevant URLs
            classLoader = new IsolatedURLClassLoader(allURLs.toArray(new URL[0]));
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating URLClassLoader", e);
        }
    
        // Run the whole JUnit testing from a class loaded by this ClassLoader
        try {
            // Load the ClassLoaderIsolatedTestRunner class using the custom class loader
            Class<?> testRunnerClass = classLoader.loadClass(ClassLoaderIsolatedTestRunner.class.getName());
            Constructor<?> constructor = testRunnerClass.getDeclaredConstructor();

            // Ensure the constructor is accessible and create an instance using the constructor
            constructor.setAccessible(true);
            Object testRunner = constructor.newInstance();
    
            // Invoke the JUnit runner method reflectively
            Method runMethod = testRunnerClass.getMethod("runInvokedReflectively", List.class, ClassLoader.class, int.class);
            runMethod.invoke(testRunner, testClassNames, classLoader, numReruns);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new MojoExecutionException("Error invoking ClassLoaderIsolatedTestRunner", e);
        }
    }

    /**
     * Finds test classes recursively in the given directory.
     *
     * @param directory the directory to search for test classes
     * @return list of test class names
     */
    private List<String> findTestClasses(String directory) {
        List<String> testClassNames = new ArrayList<>();
        findTestClassesRecursive(new File(directory), testClassNames, "");
        return testClassNames;
    }

    /**
     * Recursively finds test classes in the given directory.
     *
     * @param directory the directory to search for test classes
     * @param testClassNames list to collect test class names
     * @param packageName the package name of the current directory
     */
    private void findTestClassesRecursive(File directory, List<String> testClassNames, String packageName) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findTestClassesRecursive(file, testClassNames, packageName + file.getName() + ".");
                } else if (file.getName().endsWith(".class")) {
                    String className = packageName + file.getName().replace(".class", "").replace(File.separator, ".");
                    testClassNames.add(className);
                }
            }
        }
    }
}
