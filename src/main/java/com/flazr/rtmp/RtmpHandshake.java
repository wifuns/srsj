/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp;

import com.flazr.rtmp.client.ClientOptions;
import com.flazr.util.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RtmpHandshake {

    private static final Logger logger = LoggerFactory.getLogger(RtmpHandshake.class);

    public static final int HANDSHAKE_SIZE = 1536;

    /** SHA 256 digest length */
    private static final int DIGEST_SIZE = 32;

    private static final int PUBLIC_KEY_SIZE = 128;

    private static final byte[] SERVER_CONST = "Genuine Adobe Flash Media Server 001".getBytes();

    public static final byte[] CLIENT_CONST = "Genuine Adobe Flash Player 001".getBytes();

    private static final byte[] RANDOM_CRUD = Utils.fromHex(
        "F0EEC24A8068BEE82E00D0D1029E7E576EEC5D2D29806FAB93B8E636CFEB31AE"
    );

    private static final byte[] SERVER_CONST_CRUD = concat(SERVER_CONST, RANDOM_CRUD);

    private static final byte[] CLIENT_CONST_CRUD = concat(CLIENT_CONST, RANDOM_CRUD);

    private static final byte[] DH_MODULUS_BYTES = Utils.fromHex(
    	  "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
    	+ "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
    	+ "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    	+ "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381FFFFFFFFFFFFFFFF"
    );

    private static final BigInteger DH_MODULUS = new BigInteger(1, DH_MODULUS_BYTES);

    private static final BigInteger DH_BASE = BigInteger.valueOf(2);

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private static int calculateOffset(ByteBuf in, int pointerIndex, int modulus, int increment) {
        byte[] pointer = new byte[4];
        in.getBytes(pointerIndex, pointer);
        int offset = 0;
        // sum the 4 bytes of the pointer
        for (int i = 0; i < pointer.length; i++) {
            offset += pointer[i] & 0xff;
        }
        offset %= modulus;
        offset += increment;
        return offset;
    }

    private static byte[] digestHandshake(ByteBuf in, int digestOffset, byte[] key) {
        final byte[] message = new byte[HANDSHAKE_SIZE - DIGEST_SIZE];
        in.getBytes(0, message, 0, digestOffset);
        final int afterDigestOffset = digestOffset + DIGEST_SIZE;
        in.getBytes(afterDigestOffset, message, digestOffset, HANDSHAKE_SIZE - afterDigestOffset);
        return Utils.sha256(message, key);
    }

    private static ByteBuf generateRandomHandshake() {
        byte[] randomBytes = new byte[HANDSHAKE_SIZE];
        Random random = new Random();
        random.nextBytes(randomBytes);
        return Unpooled.wrappedBuffer(randomBytes);
    }

    private static final Map<Integer, Integer> clientVersionToValidationTypeMap;

    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(0x09007c02, 1);
        map.put(0x09009702, 1);
        map.put(0x09009f02, 1);
        map.put(0x0900f602, 1);
        map.put(0x0a000202, 1);
        map.put(0x0a000c02, 1);
        map.put(0x80000102, 1);
        map.put(0x80000302, 2);
        map.put(0x0a002002, 2);
        clientVersionToValidationTypeMap = map;
    }

    protected static int getValidationTypeForClientVersion(byte[] version) {
        final int intValue = Unpooled.wrappedBuffer(version).getInt(0);
        Integer type = clientVersionToValidationTypeMap.get(intValue);
        if(type == null) {
            return 0;
        }
        return type;
    }

    private byte[] clientVersionToUse = new byte[]{0x09, 0x00, 0x7c, 0x02};

    private byte[] serverVersionToUse = new byte[]{0x03, 0x05, 0x01, 0x01};

    private static int digestOffset(ByteBuf in, int validationType) {
        switch(validationType) {
            case 1: return calculateOffset(in, 8, 728, 12);
            case 2: return calculateOffset(in, 772, 728, 776);
            default: throw new RuntimeException("cannot get digest offset for type: " + validationType);
        }
    }

    private static int publicKeyOffset(ByteBuf in, int validationType) {
        switch(validationType) {
            case 1: return calculateOffset(in, 1532, 632, 772);
            case 2: return calculateOffset(in, 768, 632, 8);
            default: throw new RuntimeException("cannot get public key offset for type: " + validationType);
        }
    }

    //==========================================================================

    private KeyAgreement keyAgreement;
    private byte[] peerVersion;
    private byte[] ownPublicKey;
    private byte[] peerPublicKey;
    private byte[] ownPartOneDigest;
    private byte[] peerPartOneDigest;
    private Cipher cipherOut;
    private Cipher cipherIn;
    private byte[] peerTime;

    private boolean rtmpe;
    private int validationType;

    private byte[] swfHash;
    private int swfSize;
    private byte[] swfvBytes;

    private ByteBuf peerPartOne;
    private ByteBuf ownPartOne;

    public RtmpHandshake() {}

    public RtmpHandshake(ClientOptions session) {
        this.rtmpe = session.isRtmpe();
        this.swfHash = session.getSwfHash();
        this.swfSize = session.getSwfSize();
        if(session.getClientVersionToUse() != null) {
            this.clientVersionToUse = session.getClientVersionToUse();
        }
    }

    public byte[] getSwfvBytes() {
        return swfvBytes;
    }

    public Cipher getCipherIn() {
        return cipherIn;
    }

    public Cipher getCipherOut() {
        return cipherOut;
    }

    public boolean isRtmpe() {
        return rtmpe;
    }

    public byte[] getPeerVersion() {
        return peerVersion;
    }

    //========================= ENCRYPT / DECRYPT ==============================

    private void cipherUpdate(final ByteBuf in, final Cipher cipher) {
        final int size = in.readableBytes();
        if(size == 0) {
            return;
        }
        final int position = in.readerIndex();
        final byte[] bytes = new byte[size];
        in.getBytes(position, bytes);
        in.setBytes(position, cipher.update(bytes));
    }

    public void cipherUpdateIn(final ByteBuf in) {
        cipherUpdate(in, cipherIn);
    }

    public void cipherUpdateOut(final ByteBuf in) {
        cipherUpdate(in, cipherOut);
    }

    //============================== PKI =======================================

    private void initKeyPair() {
        final DHParameterSpec keySpec = new DHParameterSpec(DH_MODULUS, DH_BASE);
        final KeyPair keyPair;
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("DH");
            keyGen.initialize(keySpec);
            keyPair = keyGen.generateKeyPair();
            keyAgreement = KeyAgreement.getInstance("DH");
            keyAgreement.init(keyPair.getPrivate());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // extract public key bytes
        DHPublicKey publicKey = (DHPublicKey) keyPair.getPublic();
        BigInteger dh_Y = publicKey.getY();
        ownPublicKey = dh_Y.toByteArray();
        byte[] temp = new byte[PUBLIC_KEY_SIZE];
        if (ownPublicKey.length < PUBLIC_KEY_SIZE) {
            // pad zeros on left
            System.arraycopy(ownPublicKey, 0, temp, PUBLIC_KEY_SIZE - ownPublicKey.length, ownPublicKey.length);
            ownPublicKey = temp;
        } else if (ownPublicKey.length > PUBLIC_KEY_SIZE) {
            // truncate zeros from left
            System.arraycopy(ownPublicKey, ownPublicKey.length - PUBLIC_KEY_SIZE, temp, 0, PUBLIC_KEY_SIZE);
            ownPublicKey = temp;
        }
    }

    private void initCiphers() {
        BigInteger otherPublicKeyInt = new BigInteger(1, peerPublicKey);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("DH");
            KeySpec otherPublicKeySpec = new DHPublicKeySpec(otherPublicKeyInt, DH_MODULUS, DH_BASE);
            PublicKey otherPublicKey = keyFactory.generatePublic(otherPublicKeySpec);
            keyAgreement.doPhase(otherPublicKey, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        byte[] sharedSecret = keyAgreement.generateSecret();
        byte[] digestOut = Utils.sha256(peerPublicKey, sharedSecret);
        byte[] digestIn = Utils.sha256(ownPublicKey, sharedSecret);
        try {
            cipherOut = Cipher.getInstance("RC4");
            cipherOut.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(digestOut, 0, 16, "RC4"));
            cipherIn = Cipher.getInstance("RC4");
            cipherIn.init(Cipher.DECRYPT_MODE, new SecretKeySpec(digestIn, 0, 16, "RC4"));
            logger.info("initialized encryption / decryption ciphers");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // update 'encoder / decoder state' for the RC4 keys
        // both parties *pretend* as if handshake part 2 (1536 bytes) was encrypted
        // effectively this hides / discards the first few bytes of encrypted session
        // which is known to increase the secure-ness of RC4
        // RC4 state is just a function of number of bytes processed so far
        // that's why we just run 1536 arbitrary bytes through the keys below
        byte[] dummyBytes = new byte[HANDSHAKE_SIZE];
        cipherIn.update(dummyBytes);
        cipherOut.update(dummyBytes);
    }

    //============================== CLIENT ====================================

    public ByteBuf encodeClient0() {
        ByteBuf out = Unpooled.buffer(1);
        if (rtmpe) {
            out.writeByte((byte) 0x06);
        } else {
            out.writeByte((byte) 0x03);
        }
        return out;
    }

    public ByteBuf encodeClient1() {
        ByteBuf out = generateRandomHandshake();
        out.setInt(0, 0); // zeros
        out.setBytes(4, clientVersionToUse);
        validationType = getValidationTypeForClientVersion(clientVersionToUse);
        logger.info("using client version {}", Utils.toHex(clientVersionToUse));
        if (validationType == 0) {
            ownPartOne = out.copy(); // save for later
            return out;
        }
        logger.debug("creating client part 1, validation type: {}", validationType);
        initKeyPair();
        int publicKeyOffset = publicKeyOffset(out, validationType);
        out.setBytes(publicKeyOffset, ownPublicKey);
        int digestOffset = digestOffset(out, validationType);
        ownPartOneDigest = digestHandshake(out, digestOffset, CLIENT_CONST);
        out.setBytes(digestOffset, ownPartOneDigest);
        return out;
    }

    public boolean decodeServerAll(ByteBuf in) {
        decodeServer0(in.readBytes(1));
        decodeServer1(in.readBytes(HANDSHAKE_SIZE));
        decodeServer2(in.readBytes(HANDSHAKE_SIZE));
        return true;
    }

    private void decodeServer0(ByteBuf in) {
        byte flag = in.getByte(0);
        if(rtmpe &&  flag != 0x06) {
            logger.warn("server does not support rtmpe! falling back to rtmp");
            rtmpe = false;
        }
    }

    private void decodeServer1(ByteBuf in) {
        peerTime = new byte[4];
        in.getBytes(0, peerTime);
        byte[] serverVersion = new byte[4];
        in.getBytes(4, serverVersion);
        logger.debug("server time: {}, version: {}", Utils.toHex(peerTime), Utils.toHex(serverVersion));
        if(swfHash != null) {
            // swf verification
            byte[] key = new byte[DIGEST_SIZE];
            in.getBytes(HANDSHAKE_SIZE - DIGEST_SIZE, key);
            byte[] digest = Utils.sha256(swfHash, key);
            // construct SWF verification pong payload
            ByteBuf swfv = Unpooled.buffer(42);
            swfv.writeByte((byte) 0x01);
            swfv.writeByte((byte) 0x01);
            swfv.writeInt(swfSize);
            swfv.writeInt(swfSize);
            swfv.writeBytes(digest);
            swfvBytes = new byte[42];
            swfv.readBytes(swfvBytes);
            logger.info("calculated swf verification response: {}", Utils.toHex(swfvBytes));
        }
        if(validationType == 0) {
            peerPartOne = in; // save for later
            return;
        }
        logger.debug("processing server part 1, validation type: {}", validationType);
        int digestOffset = digestOffset(in, validationType);
        byte[] expected = digestHandshake(in, digestOffset, SERVER_CONST);
        peerPartOneDigest = new byte[DIGEST_SIZE];
        in.getBytes(digestOffset, peerPartOneDigest);
        if (!Arrays.equals(peerPartOneDigest, expected)) {
            int altValidationType = validationType == 1 ? 2 : 1;
            logger.warn("server part 1 validation failed for type {}, will try with type {}",
                    validationType, altValidationType);
            digestOffset = digestOffset(in, altValidationType);
            expected = digestHandshake(in, digestOffset, SERVER_CONST);
            peerPartOneDigest = new byte[DIGEST_SIZE];
            in.getBytes(digestOffset, peerPartOneDigest);
            if (!Arrays.equals(peerPartOneDigest, expected)) {
                throw new RuntimeException("server part 1 validation failed even for type: " + altValidationType);
            }
            validationType = altValidationType;
        }
        logger.info("server part 1 validation success");
        peerPublicKey = new byte[PUBLIC_KEY_SIZE];
        int publicKeyOffset = publicKeyOffset(in, validationType);
        in.getBytes(publicKeyOffset, peerPublicKey);
        initCiphers();
    }

    private void decodeServer2(ByteBuf in) {
        if(validationType == 0) {
            return; // TODO validate random echo
        }
        logger.debug("processing server part 2 for validation");
        byte[] key = Utils.sha256(ownPartOneDigest, SERVER_CONST_CRUD);
        int digestOffset = HANDSHAKE_SIZE - DIGEST_SIZE;
        byte[] expected = digestHandshake(in, digestOffset, key);
        byte[] actual = new byte[DIGEST_SIZE];
        in.getBytes(digestOffset, actual);
        if (!Arrays.equals(actual, expected)) {
            throw new RuntimeException("server part 2 validation failed");
        }
        logger.info("server part 2 validation success");
    }

    public ByteBuf encodeClient2() {
        if(validationType == 0) {
            peerPartOne.setBytes(0, peerTime);
            peerPartOne.setInt(4, 0); // more zeros
            return peerPartOne;
        }
        logger.debug("creating client part 2 for validation");
        ByteBuf out = generateRandomHandshake();
        byte[] key = Utils.sha256(peerPartOneDigest, CLIENT_CONST_CRUD);
        int digestOffset = HANDSHAKE_SIZE - DIGEST_SIZE;
        byte[] digest = digestHandshake(out, digestOffset, key);
        out.setBytes(digestOffset, digest);
        return out;
    }

    //============================ SERVER ======================================

    public void decodeClient0And1(ByteBuf in) {
        decodeClient0(in.readBytes(1));
        decodeClient1(in.readBytes(HANDSHAKE_SIZE));
    }

    private void decodeClient0(ByteBuf in) {
        final byte firstByte = in.readByte();
        rtmpe = firstByte == 0x06;
        logger.debug("client first byte {}, rtmpe: {}", Utils.toHex(firstByte), rtmpe);
    }

    private boolean decodeClient1(ByteBuf in) {
        peerTime = new byte[4];
        in.getBytes(0, peerTime);
        peerVersion = new byte[4];
        in.getBytes(4, peerVersion);
        logger.debug("client time: {}, version: {}", Utils.toHex(peerTime), Utils.toHex(peerVersion));
        validationType = getValidationTypeForClientVersion(peerVersion);
        if(validationType == 0) {
            peerPartOne = in; // save for later
            return true;
        }
        logger.debug("processing client part 1 for validation type: {}", validationType);
        initKeyPair();
        int digestOffset = digestOffset(in, validationType);
        peerPartOneDigest = new byte[DIGEST_SIZE];
        in.getBytes(digestOffset, peerPartOneDigest);
        byte[] expected = digestHandshake(in, digestOffset, CLIENT_CONST);
        if(!Arrays.equals(peerPartOneDigest, expected)) {
            throw new RuntimeException("client part 1 validation failed");
        }
        logger.info("client part 1 validation success");
        int publicKeyOffset = publicKeyOffset(in, validationType);
        peerPublicKey = new byte[PUBLIC_KEY_SIZE];
        in.getBytes(publicKeyOffset, peerPublicKey);
        initCiphers();
        return true;
    }

    public ByteBuf encodeServer0() {
        ByteBuf out = Unpooled.buffer(1);
        out.writeByte((byte) (rtmpe ? 0x06 : 0x03));
        return out;
    }

    public ByteBuf encodeServer1() {
        ByteBuf out = generateRandomHandshake();
        out.setInt(0, 0); // zeros
        out.setBytes(4, serverVersionToUse);
        if(validationType == 0) {
            ownPartOne = out.copy();
            return out;
        }
        logger.debug("creating server part 1 for validation type: {}", validationType);
        int publicKeyOffset = publicKeyOffset(out, validationType);
        out.setBytes(publicKeyOffset, ownPublicKey);
        int digestOffset = digestOffset(out, validationType);
        ownPartOneDigest = digestHandshake(out, digestOffset, SERVER_CONST);
        out.setBytes(digestOffset, ownPartOneDigest);
        return out;
    }

    public void decodeClient2(ByteBuf raw) {
        ByteBuf in = raw.readBytes(HANDSHAKE_SIZE);
        if(validationType == 0) {
            return;
        }
        logger.debug("processing client part 2 for validation");
        byte[] key = Utils.sha256(ownPartOneDigest, CLIENT_CONST_CRUD);
        int digestOffset = HANDSHAKE_SIZE - DIGEST_SIZE;
        byte[] expected = digestHandshake(in, digestOffset, key);
        byte[] actual = new byte[DIGEST_SIZE];
        in.getBytes(digestOffset, actual);
        if (!Arrays.equals(actual, expected)) {
        	//这块需要学习一下red5
        	return ;
           // throw new RuntimeException("client part 2 validation failed");
        }
        logger.info("client part 2 validation success");
    }

    public ByteBuf encodeServer2() {
        if(validationType == 0) {
            peerPartOne.setBytes(0, peerTime); // zeros
            peerPartOne.setInt(4, 0); // more zeros
            return peerPartOne;
        }
        logger.debug("creating server part 2 for validation");
        ByteBuf out = generateRandomHandshake();
        byte[] key = Utils.sha256(peerPartOneDigest, SERVER_CONST_CRUD);
        int digestOffset = HANDSHAKE_SIZE - DIGEST_SIZE;
        byte[] digest = digestHandshake(out, digestOffset, key);
        out.setBytes(digestOffset, digest);
        return out;
    }

}
