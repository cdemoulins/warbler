/**
 * Copyright (c) 2010-2012 Engine Yard, Inc.
 * Copyright (c) 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Map;
import java.util.jar.JarEntry;

/**
 * Used as a Main-Class in the manifest for a .war file, so that you can run
 * a .war file with <tt>java -jar</tt>.
 *
 * WarMain can be used with different web server libraries. WarMain expects
 * to have two files present in the .war file,
 * <tt>WEB-INF/webserver.properties</tt> and <tt>WEB-INF/webserver.jar</tt>.
 *
 * When WarMain starts up, it extracts the webserver jar to a temporary
 * directory, and creates a temporary work directory for the webapp. Both
 * are deleted on exit.
 *
 * It then reads webserver.properties into a java.util.Properties object,
 * creates a URL classloader holding the jar, and loads and invokes the
 * <tt>main</tt> method of the main class mentioned in the properties.
 *
 * An example webserver.properties follows for Jetty. The <tt>args</tt>
 * property indicates the names and ordering of other properties to be used
 * as command-line arguments. The special tokens <tt>{{warfile}}</tt> and
 * <tt>{{webroot}}</tt> are substituted with the location of the .war file
 * being run and the temporary work directory, respectively.
 * <pre>
 * mainclass = org.eclipse.jetty.runner.Runner
 * args = args0,args1,args2,args3,args4
 * props = jetty.home
 * args0 = --port
 * args1 = {{port}}
 * args2 = --config
 * args3 = {{config}}
 * args4 = {{warfile}}
 * jetty.home = {{webroot}}
 * </pre>
 *
 * System properties can also be set via webserver.properties. For example,
 * the following entries set <tt>jetty.home</tt> before launching the server.
 * <pre>
 * props = jetty.home
 * jetty.home = {{webroot}}
 * </pre>
 */
public class WarMain extends JarMain {

    static final String MAIN = '/' + WarMain.class.getName().replace('.', '/') + ".class";
    static final String WEBSERVER_PROPERTIES = "/WEB-INF/webserver.properties";
    static final String WEBSERVER_JAR = "/WEB-INF/webserver.jar";
    static final String LOGGER_JAR = "/WEB-INF/logger.jar";
    static final String WEBSERVER_CONFIG = "/WEB-INF/webserver.xml";
    static final String WEB_INF = "WEB-INF";
    static final String META_INF = "META-INF";

    /**
     *  jruby arguments, consider the following command :
     *    `java -jar rails.war --1.9 -S rake db:migrate`
     *   arguments == [ "--1.9" ]
     *   executable == "rake"
     *   executableArgv == [ "db:migrate" ]
     */
    private final String[] arguments;

    /**
     * null to launch webserver or != null to run a executable e.g. rake
     */
    private final String executable;
    private final String[] executableArgv;

    private File webroot;

    WarMain(final String[] args) {
        super(args);
        final List<String> argsList = Arrays.asList(args);
        final int sIndex = argsList.indexOf("-S");
        if ( sIndex == -1 ) {
            executable = null; executableArgv = null; arguments = null;
        }
        else {
            if ( args.length == sIndex + 1 || args[sIndex + 1].isEmpty() ) {
                throw new IllegalArgumentException("missing executable after -S");
            }
            arguments = argsList.subList(0, sIndex).toArray(new String[0]);
            String execArg = argsList.get(sIndex + 1);
            executableArgv = argsList.subList(sIndex + 2, argsList.size()).toArray(new String[0]);

            if (execArg.equals("rails")) {
                // The rails executable doesn't play well with ScriptingContainer, so we've packaged the
                // same script that would have been generated by `rake rails:update:bin`
                execArg = "./META-INF/rails.rb";
            }
            else if (execArg.equals("bundle") && executableArgv.length > 0 && executableArgv[0].equals("exec")) {
                warn("`bundle exec' may drop out of the Warbler environment and into the system environment");
            }

            executable = execArg;
        }
    }

    private List<URL> extractWebserver() throws Exception {
        List<URL> jars = new ArrayList<URL>();
        this.webroot = File.createTempFile("warbler", "webroot");
        this.webroot.delete();
        this.webroot.mkdirs();
        this.webroot = new File(this.webroot, new File(archive).getName());
        debug("webroot directory is " + this.webroot.getPath());
        InputStream jarStream = new URI("jar", entryPath(WEBSERVER_JAR), null).toURL().openStream();
        File jarFile = File.createTempFile("webserver", ".jar");
        jarFile.deleteOnExit();
        FileOutputStream outStream = new FileOutputStream(jarFile);
        try {
            byte[] buf = new byte[4096];
            int bytesRead;
            while ((bytesRead = jarStream.read(buf)) != -1) {
                outStream.write(buf, 0, bytesRead);
            }
        } finally {
            jarStream.close();
            outStream.close();
        }
        debug("webserver.jar extracted to " + jarFile.getPath());
        jars.add(jarFile.toURI().toURL());
        jarStream = new URI("jar", entryPath(LOGGER_JAR), null).toURL().openStream();
        jarFile = File.createTempFile("logger", ".jar");
        jarFile.deleteOnExit();
        outStream = new FileOutputStream(jarFile);
        try {
            byte[] buf = new byte[4096];
            int bytesRead;
            while ((bytesRead = jarStream.read(buf)) != -1) {
                outStream.write(buf, 0, bytesRead);
            }
        } finally {
            jarStream.close();
            outStream.close();
        }
        debug("logger.jar extracted to " + jarFile.getPath());
        jars.add(jarFile.toURI().toURL());
        return jars;
    }

    private Properties getWebserverProperties() throws Exception {
        Properties props = new Properties();
        try {
            InputStream is = getClass().getResourceAsStream(WEBSERVER_PROPERTIES);
            if ( is != null ) props.load(is);
        } catch (Exception e) { }

        String port = getSystemProperty("warbler.port", getENV("PORT"));
        port = port == null ? "8080" : port;
        String host = getSystemProperty("warbler.host", "0.0.0.0");
        String webserverConfig = getSystemProperty("warbler.webserver_config", getENV("WARBLER_WEBSERVER_CONFIG"));
        String embeddedWebserverConfig = new URI("jar", entryPath(WEBSERVER_CONFIG), null).toURL().toString();
        webserverConfig = webserverConfig == null ? embeddedWebserverConfig : webserverConfig;
        for ( Map.Entry entry : props.entrySet() ) {
            String val = (String) entry.getValue();
            val = val.replace("{{warfile}}", archive).
                      replace("{{port}}", port).
                      replace("{{host}}", host).
                      replace("{{config}}", webserverConfig).
                      replace("{{webroot}}", webroot.getAbsolutePath());
            entry.setValue(val);
        }

        if (props.getProperty("props") != null) {
            String[] propsToSet = props.getProperty("props").split(",");
            for ( String key : propsToSet ) {
                setSystemProperty(key, props.getProperty(key));
            }
        }

        return props;
    }

    private void launchWebServer(List<URL> jars) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[] {jars.get(0), jars.get(1)}, Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Properties props = getWebserverProperties();
        String mainClass = props.getProperty("mainclass");
        if (mainClass == null) {
            throw new IllegalArgumentException("unknown webserver main class ("
                                               + WEBSERVER_PROPERTIES
                                               + " is missing 'mainclass' property)");
        }
        Class<?> klass = Class.forName(mainClass, true, loader);
        Method main = klass.getDeclaredMethod("main", new Class[] { String[].class });
        String[] newArgs = launchWebServerArguments(props);
        debug("invoking webserver with: " + Arrays.deepToString(newArgs));
        main.invoke(null, new Object[] { newArgs });
    }

    private String[] launchWebServerArguments(Properties props) {
        String[] newArgs = args;

        if (props.getProperty("args") != null) {
            String[] insertArgs = props.getProperty("args").split(",");
            newArgs = new String[args.length + insertArgs.length];
            for (int i = 0; i < insertArgs.length; i++) {
                newArgs[i] = props.getProperty(insertArgs[i], "");
            }
            System.arraycopy(args, 0, newArgs, insertArgs.length, args.length);
        }

        return newArgs;
    }

    // JarMain overrides to make WarMain "launchable"
    // e.g. java -jar rails.war -S rake db:migrate

    @Override
    protected String getExtractEntryPath(final JarEntry entry) {
        final String name = entry.getName();
        final String res;
        if ( name.startsWith(WEB_INF) ) {
            // WEB-INF/app/controllers/application_controller.rb ->
            // app/controllers/application_controller.rb
            res = name.substring(WEB_INF.length());
        } else if (name.startsWith(META_INF)) {
            // Keep them where they are.
            res = name;
        } else {
            // 404.html -> public/404.html
            // javascripts -> public/javascripts
            res = "/public/" + name;
        }
        return res;
    }

    @Override
    protected URL extractEntry(final JarEntry entry, final String path) throws Exception {
        // always extract but only return class-path entry URLs :
        final URL entryURL = super.extractEntry(entry, path);
        return path.endsWith(".jar") && path.startsWith("/lib/") ? entryURL : null;
    }

    @Override
    protected int launchJRuby(final URL[] jars) throws Exception {
        final Object scriptingContainer = newScriptingContainer(jars);

        invokeMethod(scriptingContainer, "setArgv", (Object) executableArgv);
        invokeMethod(scriptingContainer, "setCurrentDirectory", extractRoot.getAbsolutePath());
        initJRubyScriptingEnv(scriptingContainer);

        final Object provider = invokeMethod(scriptingContainer, "getProvider");
        final Object rubyInstanceConfig = invokeMethod(provider, "getRubyInstanceConfig");

        invokeMethod(rubyInstanceConfig, "setUpdateNativeENVEnabled", new Class[] { Boolean.TYPE }, false);

        final CharSequence execScriptEnvPre = executableScriptEnvPrefix();

        final String executablePath = locateExecutable(scriptingContainer, execScriptEnvPre);
        if ( executablePath == null ) {
            throw new IllegalStateException("failed to locate gem executable: '" + executable + "'");
        }
        invokeMethod(scriptingContainer, "setScriptFilename", executablePath);

        invokeMethod(rubyInstanceConfig, "processArguments", (Object) arguments);

        Object runtime = invokeMethod(scriptingContainer, "getRuntime");

        debug("loading resource: " + executablePath);
        Object executableInput =
            new SequenceInputStream(new ByteArrayInputStream(execScriptEnvPre.toString().getBytes()),
                                    (InputStream) invokeMethod(rubyInstanceConfig, "getScriptSource"));

        debug("invoking " + executablePath + " with: " + Arrays.toString(executableArgv));

        Object outcome = invokeMethod(runtime, "runFromMain",
                new Class[] { InputStream.class, String.class },
                executableInput, executablePath
        );
        return ( outcome instanceof Number ) ? ( (Number) outcome ).intValue() : 0;
    }

    @Deprecated
    protected String locateExecutable(final Object scriptingContainer) throws Exception {
        if ( executable == null ) {
            throw new IllegalStateException("no executable");
        }
        final File exec = new File(extractRoot, executable);
        if ( exec.exists() ) {
            return exec.getAbsolutePath();
        }
        else {
            final String script = locateExecutableScript(executable, executableScriptEnvPrefix());
            return (String) invokeMethod(scriptingContainer, "runScriptlet", script);
        }
    }

    protected String locateExecutable(final Object scriptingContainer, final CharSequence envPreScript)
        throws Exception {
        if ( executable == null ) {
            throw new IllegalStateException("no executable");
        }
        final File exec = new File(extractRoot, executable);
        if ( exec.exists() ) {
            return exec.getAbsolutePath();
        }
        else {
            final String script = locateExecutableScript(executable, envPreScript);
            return (String) invokeMethod(scriptingContainer, "runScriptlet", script);
        }
    }

    protected CharSequence executableScriptEnvPrefix() {
        final String gemsDir = new File(extractRoot, "gems").getAbsolutePath();
        final String gemfile = new File(extractRoot, "Gemfile").getAbsolutePath();
        debug("setting GEM_HOME to " + gemsDir);
        debug("... and BUNDLE_GEMFILE to " + gemfile);

        // ideally this would look up the config.override_gem_home setting
        return "ENV['GEM_HOME'] = ENV['GEM_PATH'] = '"+ gemsDir +"' \n" +
        "ENV['BUNDLE_GEMFILE'] ||= '"+ gemfile +"' \n" +
        "require 'uri:classloader:/META-INF/init.rb'";
    }

    protected String locateExecutableScript(final String executable, final CharSequence envPreScript) {
        return ( envPreScript == null ? "" : envPreScript + " \n" ) +
        "begin\n" + // locate the executable within gemspecs :
        "  require 'rubygems' unless defined?(Gem) \n" +
          "  begin\n" + // add bundled gems to load path :
          "    require 'bundler' \n" +
          "  rescue LoadError\n" + // bundler not used
          "  else\n" +
          "    env = ENV['RAILS_ENV'] || ENV['RACK_ENV'] \n" + // init.rb sets ENV['RAILS_ENV'] ||= ...
          "    env ? Bundler.setup(:default, env) : Bundler.setup(:default) \n" +
          "  end if ENV_JAVA['warbler.bundler.setup'] != 'false' \n" + // java -Dwarbler.bundler.setup=false -jar my.war -S pry
        "  exec = '"+ executable +"' \n" +
        "  spec = Gem::Specification.find { |s| s.executables.include?(exec) } \n" +
        "  spec ? spec.bin_file(exec) : nil \n" +
        // returns the full path to the executable
        "rescue SystemExit => e\n" +
        "  e.status\n" +
        "end";
    }

    protected void initJRubyScriptingEnv(Object scriptingContainer) throws Exception {
        // for some reason, the container needs to run a scriptlet in order for it
        // to be able to find the gem executables later
        invokeMethod(scriptingContainer, "runScriptlet", "SCRIPTING_CONTAINER_INITIALIZED=true");

        invokeMethod(scriptingContainer, "setHomeDirectory", "uri:classloader:/META-INF/jruby.home");
    }

    @Override
    protected int start() throws Exception {
        if ( executable == null ) {
            try {
                List<URL> server = extractWebserver();
                launchWebServer(server);
            }
            catch (FileNotFoundException e) {
                final String msg = e.getMessage();
                if ( msg != null && msg.contains("WEB-INF/webserver.jar") ) {
                    System.out.println("specify the -S argument followed by the bin file to run e.g. `java -jar rails.war -S rake -T` ...");
                    System.out.println("(or if you'd like your .war file to start a web server package it using `warbler executable war`)");
                }
                throw e;
            }
            return 0;
        }
        return super.start();
    }

    @Override
    public void run() {
        super.run();
        if ( webroot != null ) delete(webroot.getParentFile());
    }

    public static void main(String[] args) {
        doStart(new WarMain(args));
    }

}
