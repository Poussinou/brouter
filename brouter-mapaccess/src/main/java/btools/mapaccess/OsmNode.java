/**
 * Container for an osm node
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.ByteArrayUnifier;



public class OsmNode implements OsmPos
{
  public static final int EXTERNAL_BITMASK        = 0x80; // old semantic
  public static final int SIGNLON_BITMASK         = 0x80;
  public static final int SIGNLAT_BITMASK         = 0x40;
  public static final int TRANSFERNODE_BITMASK    = 0x20;
  public static final int WRITEDESC_BITMASK       = 0x10;
  public static final int SKIPDETAILS_BITMASK     = 0x08;
  public static final int NODEDESC_BITMASK        = 0x04;
  public static final int RESERVED1_BITMASK       = 0x02;
  public static final int RESERVED2_BITMASK       = 0x01;

  public OsmNode()
  {
  }

  public OsmNode( int ilon, int ilat )
  {
    this.ilon = ilon;
    this.ilat = ilat;
  }

  public OsmNode( long id )
  {
    ilon = (int)(id >> 32);
    ilat = (int)(id & 0xffffffff);
  }

  /**
   * The latitude
   */
    public int ilat;

 /**
   * The longitude
   */
    public int ilon;

 /**
   * The elevation
   */
    public short selev;

    public byte[] nodeDescription;

  // interface OsmPos
  public int getILat()
  {
    return ilat;
  }

  public int getILon()
  {
    return ilon;
  }

  public short getSElev()
  {
    return selev;
  }

  public double getElev()
  {
    return selev / 4.;
  }


 /**
   * The links to other nodes
   */
    public OsmLink firstlink = null;

    public OsmLink firstreverse = null;

   public boolean wasProcessed; // whether this node has all it's links resolved

   public void addLink( OsmLink link )
   {
     if ( firstlink != null ) link.next = firstlink;
     firstlink = link;
   }

  public int calcDistance( OsmPos p )
  {
    double l = (ilat-90000000) * 0.00000001234134;
    double l2 = l*l;
    double l4 = l2*l2;
    double coslat = 1.- l2 + l4 / 6.;

    double dlat = (ilat - p.getILat() )/1000000.;
    double dlon = (ilon - p.getILon() )/1000000. * coslat;
    double d = Math.sqrt( dlat*dlat + dlon*dlon ) * (6378000. / 57.3);
    return (int)(d + 1.0 );
  }


   public void parseNodeBody( MicroCache is, OsmNodesMap hollowNodes, DistanceChecker dc )
   {
	 ByteArrayUnifier abUnifier = hollowNodes.getByteArrayUnifier();
	   
	 selev = is.readShort();

     OsmLink lastlink = null;
     
     OsmLink firstHollowLink = firstlink;
     firstlink = null;

     while( is.hasMoreData() )
     {
       int ilonref = ilon;
       int ilatref = ilat;

       OsmLink link = new OsmLink();
       OsmTransferNode firstTransferNode = null;
       OsmTransferNode lastTransferNode = null;
       int linklon;
       int linklat;
       byte[] description = null;
       for(;;)
       {
         int bitField = is.readByte();
         int dlon = is.readVarLengthUnsigned();
         int dlat = is.readVarLengthUnsigned();
         if ( (bitField & SIGNLON_BITMASK) != 0 ) { dlon = -dlon;}
         if ( (bitField & SIGNLAT_BITMASK) != 0 ) { dlat = -dlat;}
         linklon = ilonref + dlon;
         linklat = ilatref + dlat;
         ilonref = linklon;
         ilatref = linklat;
         // read variable length or old 8 byte fixed, and ensure that 8 bytes is only fixed
         if ( (bitField & WRITEDESC_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[is.readByte()];
        	 is.readFully( ab );
        	 description = abUnifier.unify( ab );
         }
         if ( (bitField & NODEDESC_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[is.readByte()];
        	 is.readFully( ab );
        	 nodeDescription = abUnifier.unify( ab );
         }
         if ( (bitField & RESERVED1_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[is.readByte()];
        	 is.readFully( ab );
         }
         if ( (bitField & RESERVED2_BITMASK ) != 0 )
         {
        	 byte[] ab = new byte[is.readByte()];
        	 is.readFully( ab );
         }
         if ( (bitField & SKIPDETAILS_BITMASK ) != 0 )
         {
           link.counterLinkWritten = true;
         }
         
         if ( description == null && !link.counterLinkWritten ) throw new IllegalArgumentException( "internal error: missing way description!" );
         
         boolean isTransfer = (bitField & TRANSFERNODE_BITMASK ) != 0;
         if ( isTransfer )
         {
           OsmTransferNode trans = new OsmTransferNode();
           trans.ilon = linklon;
           trans.ilat = linklat;
           trans.descriptionBitmap = description;
           trans.selev = (short)(selev + is.readVarLengthSigned());
           if ( lastTransferNode == null )
           {
             firstTransferNode = trans;
           }
           else
           {
             lastTransferNode.next = trans;
           }
           lastTransferNode = trans;
         }
         else
         {
           link.descriptionBitmap = description;
           break;
         }
       }

       // performance shortcut: ignore link if out of reach
       if ( dc != null && !link.counterLinkWritten )
       {
           if ( !dc.isWithinRadius( ilon, ilat, firstTransferNode, linklon, linklat ) )
           {
            //   continue;
           }
       }

       if ( linklon == ilon && linklat == ilat )
       {
          continue; // skip self-ref
       }

       if ( lastlink == null )
       {
         firstlink = link;
       }
       else
       {
         lastlink.next = link;
       }
       lastlink = link;

       OsmNode tn = null;

       // first check the hollow links for that target
       for( OsmLink l = firstHollowLink; l != null; l = l.next )
       {
       	 OsmNode t = l.targetNode;
       	 if ( t.ilon == linklon && t.ilat == linklat )
       	 {
       	   tn = t;
       	   break;
       	 }
       }
       if ( tn == null ) // then check the hollow-nodes
       {
         long targetNodeId = ((long)linklon)<<32 | linklat;
         tn = hollowNodes.get( targetNodeId ); // target node

         if ( tn == null ) // node not yet known, create a new hollow proxy
         {
           tn = new OsmNode(linklon, linklat);
           tn.setHollow();
           hollowNodes.put( targetNodeId, tn );
         }

         OsmLink hollowLink = new OsmLink();
         hollowLink.targetNode = this;
         tn.addLink( hollowLink );  // make us known at the hollow link
       }
       link.targetNode = tn;

       link.encodeFirsttransfer(firstTransferNode);

       // compute the reverse link
       if ( !link.counterLinkWritten )
       {
           OsmLink rlink = new OsmLink();
           byte[] linkDescriptionBitmap = link.descriptionBitmap;
           rlink.ilonOrigin = tn.ilon;
           rlink.ilatOrigin = tn.ilat;
           rlink.targetNode = this;
           rlink.descriptionBitmap = linkDescriptionBitmap; // default for no transfer-nodes
           OsmTransferNode previous = null;
           OsmTransferNode rtrans = null;
           for( OsmTransferNode trans = firstTransferNode; trans != null; trans = trans.next )
           {
             if ( previous == null )
             {
                 rlink.descriptionBitmap = trans.descriptionBitmap;
             }
             else
             {
                 previous.descriptionBitmap = trans.descriptionBitmap;
             }
             rtrans = new OsmTransferNode();
             rtrans.ilon = trans.ilon;
             rtrans.ilat = trans.ilat;
             rtrans.selev = trans.selev;
             rtrans.next = previous;
             rtrans.descriptionBitmap = linkDescriptionBitmap;
             previous = rtrans;
           }
           rlink.encodeFirsttransfer(rtrans);
           rlink.next = firstreverse;
           firstreverse = rlink;
       }

     }

     hollowNodes.remove( getIdFromPos() );
   }

  public boolean isHollow()
  {
    return selev == -12345;
  }

  public void setHollow()
  {
    selev = -12345;
  }

  public long getIdFromPos()
  {
    return ((long)ilon)<<32 | ilat;
  }

  public void unlinkLink( OsmLink link )
  {
    if ( link == firstlink )
    {
      firstlink = link.next;
      return;
    }
    for( OsmLink l = firstlink; l != null; l = l.next )
    {
      if ( l.next == link )
      {
        l.next = link.next;
        return;
      }
    }
  }

   public OsmLink getReverseLink( int lon, int lat )
   {
     for( OsmLink rlink = firstreverse; rlink != null; rlink = rlink.next )
     {
       if ( rlink.ilonOrigin == lon && rlink.ilatOrigin == lat )
       {
         unlinkRLink( rlink );
         return rlink;
       }
     }
     return null;
   }

   public void unlinkRLink( OsmLink rlink )
   {
     if ( rlink == firstreverse )
     {
       firstreverse = rlink.next;
       return;
     }
     for( OsmLink l = firstreverse; l != null; l = l.next )
     {
       if ( l.next == rlink )
       {
         l.next = rlink.next;
         return;
       }
     }
   }

}
