/*
 * Software License Agreement (BSD License)
 *
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 *
 * * Redistributions of source code must retain the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer in the documentation and/or other
 *   materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Author: Chris Grzegorczyk grze@cs.ucsb.edu
 */

package edu.ucsb.eucalyptus.cloud.ws;

import edu.ucsb.eucalyptus.cloud.EucalyptusCloudException;
import edu.ucsb.eucalyptus.cloud.cluster.*;
import edu.ucsb.eucalyptus.cloud.entities.EntityWrapper;
import edu.ucsb.eucalyptus.cloud.state.Snapshot;
import edu.ucsb.eucalyptus.cloud.state.Volume;
import edu.ucsb.eucalyptus.keys.Hashes;
import edu.ucsb.eucalyptus.msgs.*;
import edu.ucsb.eucalyptus.util.*;
import org.apache.log4j.Logger;

import java.util.*;

public class VolumeManager {
  static String PERSISTENCE_CONTEXT = "eucalyptus.volumes";

  static {
    System.setProperty( PERSISTENCE_CONTEXT, PERSISTENCE_CONTEXT );
  }

  private static String ID_PREFIX = "vol";
  private static Logger LOG = Logger.getLogger( VolumeManager.class );

  public static EntityWrapper<Volume> getEntityWrapper() {
    return new EntityWrapper<Volume>( PERSISTENCE_CONTEXT );
  }

  public CreateVolumeResponseType CreateVolume( CreateVolumeType request ) throws EucalyptusCloudException {
    if ( !Clusters.getInstance().contains( request.getAvailabilityZone() ) ) {
      throw new EucalyptusCloudException( "Zone does not exist: " + request.getAvailabilityZone() );
    }

    EntityWrapper<Volume> db = VolumeManager.getEntityWrapper();
    if ( !(request.getSnapshotId() == null )) {
      String userName = request.isAdministrator()?null:request.getUserId();
      try {
        db.recast( Snapshot.class ).getUnique( new Snapshot( userName, request.getSnapshotId() ) );
      } catch ( EucalyptusCloudException e ) {
        LOG.debug( e, e );
        db.rollback();
        throw new EucalyptusCloudException( "Snapshot does not exist: " + request.getSnapshotId() );
      }
    }
    String newId = null;
    while ( true ) {
      newId = Hashes.generateId( request.getUserId(), ID_PREFIX );
      try {
        db.getUnique( new Volume( null, newId ) );
        break;
      } catch ( EucalyptusCloudException e ) {}
    }

    //:: TODO-1.5: there is a race here, forsooth :://
    CreateStorageVolumeType scRequest = new CreateStorageVolumeType( newId, request.getSize(), request.getSnapshotId() );
    CreateStorageVolumeResponseType scReply = null;
    try {
      scReply = ( CreateStorageVolumeResponseType ) Messaging.send( StorageProperties.STORAGE_REF, scRequest );
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback();
      throw new EucalyptusCloudException( "Error calling CreateStorageVolume:" + e.getMessage() );
    }

    Volume newVol = new Volume(
        request.getUserId(), newId, new Integer( request.getSize() ),
        request.getAvailabilityZone(), request.getSnapshotId()
    );

    db.add( newVol );
    db.commit();
    CreateVolumeResponseType reply = ( CreateVolumeResponseType ) request.getReply();
    reply.setVolume( newVol.morph( new edu.ucsb.eucalyptus.msgs.Volume() ) );
    return reply;
  }

  public DeleteVolumeResponseType DeleteVolume( DeleteVolumeType request ) throws EucalyptusCloudException {
    DeleteVolumeResponseType reply = ( DeleteVolumeResponseType ) request.getReply();
    reply.set_return( false );
    EntityWrapper<Volume> db = VolumeManager.getEntityWrapper();
    String userName = request.isAdministrator() ? null : request.getUserId();
    try {
      Volume vol = db.getUnique( new Volume( userName, request.getVolumeId() ) );
      //:: TODO-1.5: state checks and snapshot tree check here :://
      Messaging.dispatch( StorageProperties.STORAGE_REF, new DeleteStorageVolumeType( vol.getDisplayName() ) );
      db.delete( vol );
      db.commit();
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback();
      throw new EucalyptusCloudException( "Error deleting storage volume:" + e.getMessage() );
    }
    reply.set_return( true );
    return reply;
  }

  public DescribeVolumesResponseType DescribeVolumes( DescribeVolumesType request ) throws EucalyptusCloudException {
    DescribeVolumesResponseType reply = ( DescribeVolumesResponseType ) request.getReply();
    EntityWrapper<Volume> db = getEntityWrapper();
    String userName = request.isAdministrator() ? null : request.getUserId();
    List<Volume> volumes = db.query( new Volume( userName, null ) );
    for ( Volume v : volumes ) {
      if ( request.getVolumeSet().isEmpty() || request.getVolumeSet().contains( v.getDisplayName() ) ) {
        reply.getVolumeSet().add( v.morph( new edu.ucsb.eucalyptus.msgs.Volume() ) );
      }
    }
    db.commit();
    return reply;
  }

  public AttachVolumeResponseType AttachVolume( AttachVolumeType request ) throws EucalyptusCloudException {
    AttachVolumeResponseType reply = ( AttachVolumeResponseType ) request.getReply();

    VmInstance vm = null;
    try {
      vm = VmInstances.getInstance().lookup( request.getInstanceId() );
    } catch ( NoSuchElementException e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( "Instance does not exist: " + request.getInstanceId() );
    }

    for( VmInstance v : VmInstances.getInstance().listValues() ) {
      for( AttachedVolume vol : v.getVolumes() ) {
        if( vol.getVolumeId().equals( request.getVolumeId() ) ) {
          throw new EucalyptusCloudException( "Volume already attached: " + request.getVolumeId() );
        }
      }
    }
    //:: TODO-1.5: there is a potential race here :://
    EntityWrapper<Volume> db = VolumeManager.getEntityWrapper();
    String userName = request.isAdministrator()?null:request.getUserId();
    try {
      db.getUnique( new Volume( userName, request.getVolumeId() ) );
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback();
      throw new EucalyptusCloudException( "Volume does not exist: " + request.getVolumeId() );
    }
    //:: TODO-1.5: dispatch message to the cc backend here :://
    return reply;
  }

  public DetachVolumeResponseType DetachVolume( DetachVolumeType request ) throws EucalyptusCloudException {
    DetachVolumeResponseType reply = ( DetachVolumeResponseType ) request.getReply();

    VmInstance vm = null;
    try {
      vm = VmInstances.getInstance().lookup( request.getInstanceId() );
    } catch ( NoSuchElementException e ) {
      LOG.debug( e, e );
      throw new EucalyptusCloudException( "Instance does not exist: " + request.getInstanceId() );
    }
    //:: TODO-1.5: there is a potential race here :://
    EntityWrapper<Volume> db = VolumeManager.getEntityWrapper();
    String userName = request.isAdministrator()?null:request.getUserId();
    try {
      db.getUnique( new Volume( userName, request.getVolumeId() ) );
    } catch ( EucalyptusCloudException e ) {
      LOG.debug( e, e );
      db.rollback();
      throw new EucalyptusCloudException( "Volume does not exist: " + request.getVolumeId() );
    }
    db.commit();
    AttachedVolume volume = null;
    for( VmInstance v : VmInstances.getInstance().listValues() ) {
      for( AttachedVolume vol : v.getVolumes() ) {
        if( vol.getVolumeId().equals( request.getVolumeId() ) ) {
          volume = vol;
        }
      }
    }
    if( volume == null )
        throw new EucalyptusCloudException( "Volume is not attached: "+ request.getVolumeId() );

    //:: TODO-1.5: dispatch volume detach message to cc backend here :://
    reply.setDetachedVolume( volume );
    return reply;
  }

}

