package org.embulk.cli;

import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.List;
import org.jruby.RubyInstanceConfig;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;
import org.jruby.util.cli.Options;

/**
 * EmbulkGlobalJRubyScriptingContainer creates a ScriptingContainer instance for global use in Embulk.
 *
 * The creator method is static because the target instance is singleton by definition.
 */
public class EmbulkGlobalJRubyScriptingContainer
{
    private EmbulkGlobalJRubyScriptingContainer()
    {
        // Do not instantiate.
    }

    /**
     * Sets up a ScriptingContainer instance for global use in Embulk.
     */
    public static ScriptingContainer setup(
            final String[] embulkArgs,
            final List<String> jrubyOptions,
            final String bundlePath,
            final PrintStream warning)
    {
        // The JRuby instance is a global singleton so that the settings here affects later execution.
        // The local variable should be persistent so that local variables are set through ScriptingContainer.put.
        final ScriptingContainer jrubyGlobalContainer =
            new ScriptingContainer(LocalContextScope.SINGLETON, LocalVariableBehavior.PERSISTENT);
        final RubyInstanceConfig jrubyGlobalConfig = jrubyGlobalContainer.getProvider().getRubyInstanceConfig();

        for (final String jrubyOption : jrubyOptions) {
            try {
                processJRubyOption(jrubyOption, jrubyGlobalConfig);
            }
            catch (UnrecognizedJRubyOptionException ex) {
                warning.println("[WARN] The \"-R\" option(s) are not recognized in Embulk: -R" + jrubyOption);
                warning.println("[WARN] Please add your requests at: https://github.com/embulk/embulk/issues/707");
                warning.println("");
            }
            catch (NotWorkingJRubyOptionException ex) {
                warning.println("[WARN] The \"-R\" option(s) do not work in Embulk: -R" + jrubyOption);
                warning.println("");
            }
        }

        if (bundlePath != null) {
            /* Environment variables are set in the selfrun script or bin/embulk:
             *   ENV['EMBULK_BUNDLE_PATH']: set through '-b' | '--bundle', or inherit from the runtime environment
             *   ENV['BUNDLE_GEMFILE']: set for "ENV['EMBULK_BUNDLE_PATH']/Gemfile"
             *   ENV['GEM_HOME']: unset
             *   ENV['GEM_PATH']: unset
             */

            // bundler is included in embulk-core.jar
            jrubyGlobalContainer.runScriptlet("Gem.clear_paths");
            jrubyGlobalContainer.runScriptlet("require 'bundler'");

            jrubyGlobalContainer.runScriptlet("Bundler.load.setup_environment");
            jrubyGlobalContainer.runScriptlet("require 'bundler/setup'");
            // since here, `require` may load files of different (newer) embulk versions
            // especially following 'embulk/command/embulk_main'.

            // NOTE: It is intentionally not done by building a Ruby statement string from |bundlePath|.
            // It can cause insecure injections.
            //
            // add bundle directory path to load local plugins at ./embulk
            jrubyGlobalContainer.put("__internal_bundle_path__", bundlePath);
            jrubyGlobalContainer.runScriptlet("$LOAD_PATH << File.expand_path(__internal_bundle_path__)");
            jrubyGlobalContainer.remove("__internal_bundle_path__");

            return jrubyGlobalContainer;
        }
        else {
            /* Environment variables are set in the selfrun script or bin/embulk:
             *   ENV['EMBULK_BUNDLE_PATH']: unset
             *   ENV['BUNDLE_GEMFILE']: unset
             *   ENV['GEM_HOME']: set for "~/.embulk/jruby/${ruby-version}"
             *   ENV['GEM_PATH']: set for ""
             */

            jrubyGlobalContainer.runScriptlet("Gem.clear_paths");  // force rubygems to reload GEM_HOME

            // NOTE: The path from |getEmbulkJRubyLoadPath()| is added in $LOAD_PATH just in case.
            // Though it is not mandatory just to run "embulk_main.rb", it may be required in later steps.
            //
            // NOTE: It is intentionally not done by building a Ruby statement string from |getEmbulkJRubyLoadPath()|.
            // It can cause insecure injections.
            //
            // NOTE: It was written in Ruby as follows:
            //   $LOAD_PATH << File.expand_path('../../', File.dirname(__FILE__))
            jrubyGlobalContainer.put("__internal_load_path__", getEmbulkJRubyLoadPath());
            jrubyGlobalContainer.runScriptlet("$LOAD_PATH << File.expand_path(__internal_load_path__)");
            jrubyGlobalContainer.remove("__internal_load_path__");

            return jrubyGlobalContainer;
        }
    }

    private static final class UnrecognizedJRubyOptionException extends Exception {}
    private static final class NotWorkingJRubyOptionException extends Exception {}

    private static void processJRubyOption(final String jrubyOption, final RubyInstanceConfig jrubyGlobalConfig)
            throws UnrecognizedJRubyOptionException, NotWorkingJRubyOptionException
    {
        if (jrubyOption.charAt(0) != '-') {
            throw new UnrecognizedJRubyOptionException();
        }

        for (int index = 1; index < jrubyOption.length(); ++index) {
            switch (jrubyOption.charAt(index)) {
            case '-':
                if (jrubyOption.equals("--dev")) {
                    // They are not all of "--dev", but they are most possible configurations after JVM boot.
                    Options.COMPILE_INVOKEDYNAMIC.force("false");  // NOTE: Options is global.
                    jrubyGlobalConfig.setCompileMode(RubyInstanceConfig.CompileMode.OFF);
                    return;
                }
                else if (jrubyOption.equals("--client")) {
                    throw new NotWorkingJRubyOptionException();
                }
                else if (jrubyOption.equals("--server")) {
                    throw new NotWorkingJRubyOptionException();
                }
                throw new UnrecognizedJRubyOptionException();
            default:
                throw new UnrecognizedJRubyOptionException();
            }
        }
    }

    /**
     * Returns a path to be added in JRuby's $LOAD_PATH.
     *
     * In case Embulk runs from the Embulk JAR file (normal case):
     *     "file:/some/directory/embulk.jar!"
     *
     * In case Embulk runs out of a JAR file (irregular case):
     *     "/some/directory"
     */
    private static String getEmbulkJRubyLoadPath()
    {
        final ProtectionDomain protectionDomain;
        try {
            protectionDomain = EmbulkGlobalJRubyScriptingContainer.class.getProtectionDomain();
        }
        catch (SecurityException ex) {
            throw new EmbulkCommandLineException("Failed to achieve ProtectionDomain", ex);
        }

        final CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            throw new EmbulkCommandLineException("Failed to achieve CodeSource");
        }

        final URL locationUrl = codeSource.getLocation();
        if (locationUrl == null) {
            throw new EmbulkCommandLineException("Failed to achieve location");
        }
        else if (!locationUrl.getProtocol().equals("file")) {
            throw new EmbulkCommandLineException("Invalid location: " + locationUrl.toString());
        }

        final Path locationPath;
        try {
            locationPath = Paths.get(locationUrl.toURI().getPath());
        }
        catch (URISyntaxException ex) {
            throw new EmbulkCommandLineException("Invalid location: " + locationUrl.toString(), ex);
        }

        if (Files.isDirectory(locationPath)) {  // Out of a JAR file
            System.err.println("Warning: Embulk looks running out of the Embulk jar file. It is unsupported.");
            return locationPath.toString();
        }

        // TODO: Consider checking the file is really a JAR file.
        return locationUrl.toString() + "!";  // Inside the Embulk JAR file
    }
}
