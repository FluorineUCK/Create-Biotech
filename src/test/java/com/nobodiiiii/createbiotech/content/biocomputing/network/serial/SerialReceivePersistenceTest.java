package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class SerialReceivePersistenceTest {
    static SerialDevice device(){return new SerialDevice(()->SerialDeviceTest.HARDWARE,()->true,()->SerialDeviceTest.CODEC,()->{});}
    @Test void strictV6KeepsDistinctHeadsAndPrivateProvenanceWhileOldV5SchemasStillDrain() {
        var source=device();source.configure("s0",Direction.EAST,true);var queue=source.attachments().getFirst().receiver();byte[] bytes=SerialRecordCodec.encode(SerialDeviceTest.RECORD,SerialDeviceTest.CODEC);
        var origin=new SharedSerialChannel.Port(UUID.randomUUID(),"s0");queue.offer(bytes,origin);queue.offer(bytes,origin);
        UUID first=queue.head().orElseThrow().identity();var save=(CompoundTag)source.write();assertEquals(6,save.getInt("Version"));
        var old5=save.copy();old5.putInt("Version",5);old5.remove("ReceiveKey");var v5=device();v5.read(old5);assertEquals(first,v5.beginReceive("s0").head().identity());assertEquals(source.beginReceive("s0").face(),v5.beginReceive("s0").face());assertEquals(SerialDeviceTest.RECORD,v5.poll("s0").orElseThrow());
        var restored=device();restored.read(save);assertEquals(first,restored.beginReceive("s0").head().identity());assertEquals(Optional.of(origin),restored.prepareReceive("s0").source());assertEquals(SerialDeviceTest.RECORD,restored.poll("s0").orElseThrow());assertNotEquals(first,restored.beginReceive("s0").head().identity());
        var old4=save.copy();old4.remove("ReceiveKey");old4.putInt("Version",4);var port=old4.getCompound("Ports").getCompound("s0");port.remove("Interface");port.remove("ConfigurationEpoch");port.remove("HardwareEpoch");for(Tag raw:port.getList("Frames",10))((CompoundTag)raw).remove("Frame");
        var compatible=device();compatible.read(old4);assertEquals(SerialDeviceTest.RECORD,compatible.poll("s0").orElseThrow());assertEquals(SerialDeviceTest.RECORD,compatible.poll("s0").orElseThrow());
        var old1=old4.copy();old1.putInt("Version",1);var rawFrames=new ListTag();rawFrames.add(new ByteArrayTag(bytes));old1.getCompound("Ports").getCompound("s0").put("Frames",rawFrames);var oldest=device();oldest.read(old1);assertEquals(SerialDeviceTest.RECORD,oldest.poll("s0").orElseThrow());
    }
    @Test void malformedFutureDuplicateIdentityAndNegativeEpochDeviceSavesRemainOpaque() {
        var source=device();source.configure("s0",Direction.EAST,true);var queue=source.attachments().getFirst().receiver();byte[] bytes=SerialRecordCodec.encode(SerialDeviceTest.RECORD,SerialDeviceTest.CODEC);queue.offer(bytes);queue.offer(bytes);
        for(String corrupt:List.of("version","frame","interface","duplicate","epoch","extra","key")) {
            var save=(CompoundTag)source.write();var port=save.getCompound("Ports").getCompound("s0");var frames=port.getList("Frames",10);
            switch(corrupt){case "version"->save.putInt("Version",7);case "frame"->frames.getCompound(0).remove("Frame");case "interface"->port.remove("Interface");case "duplicate"->frames.getCompound(1).putUUID("Frame",frames.getCompound(0).getUUID("Frame"));case "epoch"->port.putLong("ConfigurationEpoch",-1);case "extra"->frames.getCompound(0).putInt("unexpected",1);case "key"->save.putByteArray("ReceiveKey",new byte[31]);default->throw new AssertionError();}
            var restored=device();restored.read(save);assertTrue(restored.diagnostic().isPresent(),corrupt);assertTrue(restored.attachments().isEmpty());assertEquals(save,restored.write());
        }
    }
    @Test void editingReceiveKeyInWriteSnapshotCannotChangeLiveCredentialOrFreshReload() {
        var source=device();source.configure("s0",Direction.EAST,true);source.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialDeviceTest.RECORD,SerialDeviceTest.CODEC));var receive=source.beginReceive("s0");
        var cursor=new SerialReceiveProof.Cursor(receive.device(),receive.face(),receive.head().identity(),UUID.randomUUID(),"0".repeat(64),0,0,1,receive.head().size(),0,0,false,"s0",receive.limits(),receive.configuration(),"");var credential=source.signReceiveCursor(cursor);assertTrue(source.verifyReceiveCursor(cursor,credential));
        var snapshot=(CompoundTag)source.write();byte[] original=snapshot.getByteArray("ReceiveKey").clone();snapshot.getByteArray("ReceiveKey")[0]^=1;
        assertArrayEquals(original,((CompoundTag)source.write()).getByteArray("ReceiveKey"),"Returned NBT must not borrow the live private key");assertTrue(source.verifyReceiveCursor(cursor,credential));
        var restored=device();restored.read(source.write());assertTrue(restored.verifyReceiveCursor(cursor,credential));assertEquals(receive.head().identity(),restored.beginReceive("s0").head().identity());assertEquals(SerialDeviceTest.RECORD,restored.poll("s0").orElseThrow());
    }
    @Test void oldLibraryReceiveReplyingAdapterStillAtomicallyRepliesAfterV5AndV6Restore() {
        for(int version:List.of(5,6)) {
        var source=device();source.configure("s0",Direction.EAST,true);source.attachments().getFirst().receiver().offer(SerialRecordCodec.encode(SerialDeviceTest.RECORD,SerialDeviceTest.CODEC),new SharedSerialChannel.Port(UUID.randomUUID(),"s0"));
        var saved=(CompoundTag)source.write();saved.putInt("Version",version);if(version==5)saved.remove("ReceiveKey");var restored=device();restored.read(saved);var receive=restored.prepareReceive("s0");assertTrue(receive.source().isPresent());var before=restored.write();
        var exchange=receive.replying(SerialDeviceTest.RECORD);assertEquals(before,restored.write());assertTrue(exchange.publish());assertEquals(0,restored.status("s0").receive().frames());assertEquals("queued",restored.status("s0").transmit());assertFalse(exchange.publish());
        var after=device();after.read(restored.write());assertEquals("interrupted",after.status("s0").transmit());assertEquals(0,after.status("s0").receive().frames());
        }
    }
}
