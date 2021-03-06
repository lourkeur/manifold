package manifold.internal.host;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import manifold.api.fs.IDirectory;
import manifold.api.fs.IFileSystem;
import manifold.api.fs.def.FileSystemImpl;
import manifold.internal.runtime.UrlClassLoaderWrapper;
import manifold.util.BytecodeOptions;
import manifold.util.NecessaryEvilUtil;
import manifold.util.concurrent.LocklessLazyVar;

/**
 */
public class Manifold
{
  private static final Manifold INSTANCE = new Manifold();

  private List<File> _classpath;
  private DefaultSingleModule _module;
  private LocklessLazyVar<IFileSystem> _fileSystem = LocklessLazyVar.make(
    () ->
    {
      //noinspection ConstantConditions
      if( BytecodeOptions.JDWP_ENABLED.get() )
      {
        return new FileSystemImpl( IFileSystem.CachingMode.NO_CACHING );
      }
      return new FileSystemImpl( IFileSystem.CachingMode.FULL_CACHING );
    }
  );

  public static Manifold instance()
  {
    return INSTANCE;
  }

  private Manifold()
  {
  }

  static boolean isBootstrapped()
  {
    return ManifoldHost.isBootstrapped();
  }

  public DefaultSingleModule getModule()
  {
    return _module;
  }

  /**
   * Initializes Manifold using the classpath derived from the current classloader and system classpath.
   */
  public void init()
  {
    init( null, null );
  }

  public void init( List<File> sourcepath, List<File> classpath )
  {
    List<File> combined = new ArrayList<>();
    if( classpath != null )
    {
      combined.addAll( classpath );
    }
    NecessaryEvilUtil.bypassJava9Security();
    combined.addAll( deriveClasspathFrom( Manifold.class ) );
    setPaths( sourcepath, combined );
  }

  public void setPaths( List<File> sourcepath, List<File> classpath )
  {
    classpath = new ArrayList<>( classpath );
    removeDups( classpath );

    if( classpath.equals( _classpath ) )
    {
      return;
    }

    _classpath = classpath;

    List<IDirectory> cp = createDefaultClassPath();
    cp.addAll( classpath.stream().map( file -> ManifoldHost.getFileSystem().getIDirectory( file ) ).collect( Collectors.toList() ) );
    removeDups( cp );
    List<IDirectory> sp = sourcepath.stream().map( file -> ManifoldHost.getFileSystem().getIDirectory( file ) ).filter( e -> !excludeFromSourcePath( e.toJavaFile().getAbsolutePath() ) ).collect( Collectors.toList() );

    List<IDirectory> all = new ArrayList<>();
    for( IDirectory p : sp )
    {
      if( !all.contains( p ) )
      {
        all.add( p );
      }
    }
    for( IDirectory p : cp )
    {
      if( !all.contains( p ) )
      {
        if( !excludeFromSourcePath( p.toJavaFile().getAbsolutePath() ) )
        {
          all.add( p );
        }
      }
    }

    initDirPaths( cp, all, Collections.emptyList() );
  }

  public static boolean excludeFromSourcePath( String p )
  {
    warnIfRoot( p );
    String path = p.replace( File.separatorChar, '/' ).toLowerCase();
    return isJrePath(path) ||
           path.contains( "/idea_rt.jar" );
  }

  private static boolean isJrePath( String path ) 
  {
    if( path.endsWith( "tools.jar" ) )
    {
      return true;
    }

    String extDirs = System.getProperty("java.ext.dirs");
    if( extDirs != null && extDirs.contains( path ) )
    {
      return true;
    }
    
    String bootPath = System.getProperty("sun.boot.class.path");
    if( bootPath != null && bootPath.contains( path ) )
    {
      return true;
    }
    
    String javaHome = System.getProperty("java.home");
    if( javaHome == null )
    {
      return false;
    }
    
    return path.startsWith( javaHome );
  }

  private static void warnIfRoot( String p )
  {
    for( File root : File.listRoots() )
    {
      if( new File( p ).equals( root ) )
      {
        System.out.println( "!!!" );
        System.out.println( "WARNING: Root file " + p + " is in the Manifold classpath" );
        System.out.println( "!!!" );
      }
    }
  }

  public void initDirPaths( List<IDirectory> classpath,
                            List<IDirectory> sourcePath,
                            List<IDirectory> outputPath )
  {
    DefaultSingleModule singleModule = new DefaultSingleModule( classpath, sourcePath, outputPath );

    // Must assign _module BEFORE we initializeTypeManifolds() to prevent double bootstrapping
    // stemming from an embedded demo() call in a source producer class
    _module = singleModule;

    singleModule.initializeTypeManifolds();
  }

  public void initPaths( List<String> classpath, List<String> sourcePath, List<String> outputPath )
  {
    IFileSystem fs = ManifoldHost.getFileSystem();
    List<IDirectory> cp = classpath.stream().map( path -> fs.getIDirectory( new File( path ) ) ).collect( Collectors.toList() );
    List<IDirectory> sp = sourcePath.stream().map( path -> fs.getIDirectory( new File( path ) ) ).collect( Collectors.toList() );
    List<IDirectory> op = outputPath.stream().map( path -> fs.getIDirectory( new File( path ) ) ).collect( Collectors.toList() );
    initDirPaths( cp, sp, op );
  }

  public List<File> getClasspath()
  {
    return _classpath;
  }

  public List<IDirectory> createDefaultClassPath()
  {
    List<String> vals = new ArrayList<>();
    vals.add( removeQuotes( System.getProperty( "java.class.path", "" ) ) );
    vals.add( System.getProperty( "sun.boot.class.path", "" ) );
    vals.add( System.getProperty( "java.ext.dirs", "" ) );

    return expand( vals );
  }


  private static List<IDirectory> expand( List<String> paths )
  {
    LinkedHashSet<IDirectory> expanded = new LinkedHashSet<>();
    for( String path : paths )
    {
      for( String pathElement : path.split( File.pathSeparator ) )
      {
        if( pathElement.length() > 0 )
        {
          File filePath = new File( pathElement );
          IDirectory resource = ManifoldHost.getFileSystem().getIDirectory( filePath );
          expanded.add( resource );
        }
      }
    }
    return new ArrayList<>( expanded );
  }

  /**
   * trims leading and/or trailing double quotes
   * we've only seen this behavior on linux/macOS
   */
  private static String removeQuotes( String classpath )
  {
    if( classpath.startsWith( "\"" ) )
    {
      classpath = classpath.substring( 1 );
    }
    if( classpath.endsWith( "\"" ) )
    {
      classpath = classpath.substring( 0, classpath.length() - 1 );
    }
    return classpath;
  }

  private static void removeDups( List classpath )
  {
    for( int i = classpath.size() - 1; i >= 0; i-- )
    {
      Object f = classpath.get( i );
      classpath.remove( i );
      if( !classpath.contains( f ) )
      {
        //noinspection unchecked
        classpath.add( i, f );
      }
    }
  }

  private List<File> deriveClasspathFrom( Class clazz )
  {
    List<File> ll = new LinkedList<>();
    ClassLoader loader = clazz.getClassLoader();
    while( loader != null )
    {
      UrlClassLoaderWrapper wrap = UrlClassLoaderWrapper.wrap( loader );
      if( wrap != null )
      {
        for( URL url : wrap.getURLs() )
        {
          try
          {
            File file = new File( url.toURI() );
            if( file.exists() && !ll.contains( file ) )
            {
              ll.add( file );
            }
          }
          catch( Exception e )
          {
            //ignore
          }
        }
      }
      loader = loader.getParent();
    }
//    addBootstrapClasses( ll );
    return ll;
  }

//  private void addBootstrapClasses( List<File> ll )
//  {
//    try
//    {
//      Method m;
//      try
//      {
//        m = ClassLoader.class.getDeclaredMethod( "getBootstrapClassPath" );
//      }
//      catch( NoSuchMethodException nsme )
//      {
//        // The VM that does not define getBootstrapClassPath() seems to be the IBM VM (v. 8).
//        getBootstrapForIbm( ll );
//        return;
//      }
//      m.setAccessible( true );
//      URLClassPath bootstrapClassPath = (URLClassPath)m.invoke( null );
//      for( URL url : bootstrapClassPath.getURLs() )
//      {
//        try
//        {
//          File file = new File( url.toURI() );
//          if( file.exists() && !ll.contains( file ) )
//          {
//            ll.add( file );
//          }
//        }
//        catch( Exception e )
//        {
//          //ignore
//        }
//      }
//    }
//    catch( Exception e )
//    {
//      throw new RuntimeException( e );
//    }
//  }
//
//  private void getBootstrapForIbm( List<File> ll )
//  {
//    List<String> ibmClasspath = getJreJars();
//    ibmClasspath.forEach( e -> ll.add( new File( e ) ) );
//  }
//
//  /**
//   * Get all JARs from the lib directory of the System's java.home property
//   *
//   * @return List of absolute paths to all JRE libraries
//   */
//  public List<String> getJreJars()
//  {
//    String javaHome = System.getProperty( "java.home" );
//    Path libsDir = FileSystems.getDefault().getPath( javaHome, "/lib" );
//    List<String> retval = getIbmClasspath();
//    try
//    {
//      retval.addAll( Files.walk( libsDir )
//                       .filter( path -> path.toFile().isFile() )
//                       .filter( path -> path.toString().endsWith( ".jar" ) )
//                       .map( Path::toString )
//                       .collect( Collectors.toList() ) );
//    }
//    catch( SecurityException | IOException e )
//    {
//      e.printStackTrace();
//      throw new RuntimeException( e );
//    }
//    return retval;
//  }
//
//  /**
//   * Special handling for the unusual structure of the IBM JDK.
//   *
//   * @return A list containing the special 'vm.jar' absolute path if we are using an IBM JDK; otherwise an empty list is returned.
//   */
//  protected static List<String> getIbmClasspath()
//  {
//    List<String> retval = new ArrayList<>();
//    if( System.getProperty( "java.vendor" ).equals( "IBM Corporation" ) )
//    {
//      String fileSeparator = System.getProperty( "file.separator" );
//      String classpathSeparator = System.getProperty( "path.separator" );
//      String[] bootClasspath = System.getProperty( "sun.boot.class.path" ).split( classpathSeparator );
//      for( String entry : bootClasspath )
//      {
//        if( entry.endsWith( fileSeparator + "vm.jar" ) )
//        {
//          retval.add( entry );
//          break;
//        }
//      }
//    }
//    return retval;
//  }

  public List<String> getManifoldBootstrapJars() throws ClassNotFoundException
  {
    return Collections.singletonList( getClassLocation( "com.sun.source.tree.Tree" ) //get tools.jar
    );
  }

  private static String getClassLocation( String className ) throws ClassNotFoundException
  {
    Class clazz = Class.forName( className );

    ProtectionDomain pDomain = clazz.getProtectionDomain();
    CodeSource cSource = pDomain.getCodeSource();

    if( cSource != null )
    {
      URL loc = cSource.getLocation();
      File file;
      try
      {
        file = new File( URLDecoder.decode( loc.getPath(), StandardCharsets.UTF_8.name() ) );
      }
      catch( UnsupportedEncodingException e )
      {
        System.err.println( "Unsupported Encoding for URL: " + loc );
        System.err.println( e );
        file = new File( loc.getPath() );
      }
      return file.getPath();
    }
    else
    {
      throw new ClassNotFoundException( "Cannot find the location of the requested className <" + className + "> in classpath." );
    }
  }

  public IFileSystem getFileSystem()
  {
    return _fileSystem.get();
  }
}
