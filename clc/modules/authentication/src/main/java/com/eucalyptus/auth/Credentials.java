package com.eucalyptus.auth;

import java.security.Security;

import org.apache.log4j.Logger;
import org.apache.ws.security.WSSConfig;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.eucalyptus.util.EntityWrapper;

public class Credentials {
  static Logger LOG            = Logger.getLogger( Credentials.class );
  private static String FORMAT         = "pkcs12";
  private static String KEY_STORE_PASS = "eucalyptus";                         
  private static String FILENAME       = "euca.p12";
  private static String  DB_NAME        = "eucalyptus_auth";

  public static void init( ) {
    Security.addProvider( new BouncyCastleProvider( ) );
    org.apache.xml.security.Init.init( );
    WSSConfig.getDefaultWSConfig( ).addJceProvider( "BC", BouncyCastleProvider.class.getCanonicalName( ) );
    WSSConfig.getDefaultWSConfig( ).setTimeStampStrict( true );
    WSSConfig.getDefaultWSConfig( ).setEnableSignatureConfirmation( true );
  }

  public static boolean checkAdmin( ) {
    try {
      UserCredentialProvider.getUser( "admin" );
    } catch ( NoSuchUserException e ) {
      try {
        UserCredentialProvider.addUser( "admin", Boolean.TRUE );
      } catch ( UserExistsException e1 ) {
        LOG.fatal( e1, e1 );
        return false;
      }
    }
    return true;
  }

  public static <T> EntityWrapper<T> getEntityWrapper( ) {
    return new EntityWrapper<T>( Credentials.DB_NAME );
  }

}
