/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.security.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.*;
import java.text.MessageFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import sun.security.ssl.ClientHello.ClientHelloMessage;
import sun.security.ssl.SSLExtension.ExtensionConsumer;
import sun.security.ssl.SSLExtension.SSLExtensionSpec;
import sun.security.ssl.SSLHandshake.HandshakeMessage;
import static sun.security.ssl.SSLExtension.*;

/**
 * Pack of the "pre_shared_key" extension.
 */
final class PreSharedKeyExtension {
    static final HandshakeProducer chNetworkProducer =
            new CHPreSharedKeyProducer();
    static final ExtensionConsumer chOnLoadConsumer =
            new CHPreSharedKeyConsumer();
    static final HandshakeAbsence chOnLoadAbsence =
            new CHPreSharedKeyAbsence();
    static final HandshakeConsumer chOnTradeConsumer =
            new CHPreSharedKeyUpdate();
    static final SSLStringizer chStringizer =
            new CHPreSharedKeyStringizer();

    static final HandshakeProducer shNetworkProducer =
            new SHPreSharedKeyProducer();
    static final ExtensionConsumer shOnLoadConsumer =
            new SHPreSharedKeyConsumer();
    static final HandshakeAbsence shOnLoadAbsence =
            new SHPreSharedKeyAbsence();
    static final SSLStringizer shStringizer =
            new SHPreSharedKeyStringizer();

    private static final class PskIdentity {
        final byte[] identity;
        final int obfuscatedAge;

        PskIdentity(byte[] identity, int obfuscatedAge) {
            this.identity = identity;
            this.obfuscatedAge = obfuscatedAge;
        }

        int getEncodedLength() {
            return 2 + identity.length + 4;
        }

        void writeEncoded(ByteBuffer m) throws IOException {
            Record.putBytes16(m, identity);
            Record.putInt32(m, obfuscatedAge);
        }

        @Override
        public String toString() {
            return "{" + Utilities.toHexString(identity) + "," +
                obfuscatedAge + "}";
        }
    }

    private static final
            class CHPreSharedKeySpec implements SSLExtensionSpec {
        final List<PskIdentity> identities;
        final List<byte[]> binders;

        CHPreSharedKeySpec(List<PskIdentity> identities, List<byte[]> binders) {
            this.identities = identities;
            this.binders = binders;
        }

        CHPreSharedKeySpec(HandshakeContext context,
                ByteBuffer m) throws IOException {
            // struct {
            //     PskIdentity identities<7..2^16-1>;
            //     PskBinderEntry binders<33..2^16-1>;
            // } OfferedPsks;
            if (m.remaining() < 44) {
                context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                    "Invalid pre_shared_key extension: " +
                    "insufficient data (length=" + m.remaining() + ")");
            }

            int idEncodedLength = Record.getInt16(m);
            if (idEncodedLength < 7) {
                context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                    "Invalid pre_shared_key extension: " +
                    "insufficient identities (length=" + idEncodedLength + ")");
            }

            identities = new ArrayList<>();
            int idReadLength = 0;
            while (idReadLength < idEncodedLength) {
                byte[] id = Record.getBytes16(m);
                if (id.length < 1) {
                    context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "Invalid pre_shared_key extension: " +
                        "insufficient identity (length=" + id.length + ")");
                }
                int obfuscatedTicketAge = Record.getInt32(m);

                PskIdentity pskId = new PskIdentity(id, obfuscatedTicketAge);
                identities.add(pskId);
                idReadLength += pskId.getEncodedLength();
            }

            if (m.remaining() < 35) {
                context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "Invalid pre_shared_key extension: " +
                        "insufficient binders data (length=" +
                        m.remaining() + ")");
            }

            int bindersEncodedLen = Record.getInt16(m);
            if (bindersEncodedLen < 33) {
                context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "Invalid pre_shared_key extension: " +
                        "insufficient binders (length=" +
                        bindersEncodedLen + ")");
            }

            binders = new ArrayList<>();
            int bindersReadLength = 0;
            while (bindersReadLength < bindersEncodedLen) {
                byte[] binder = Record.getBytes8(m);
                if (binder.length < 32) {
                    context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                            "Invalid pre_shared_key extension: " +
                            "insufficient binder entry (length=" +
                            binder.length + ")");
                }
                binders.add(binder);
                bindersReadLength += 1 + binder.length;
            }
        }

        int getIdsEncodedLength() {
            int idEncodedLength = 0;
            for(PskIdentity curId : identities) {
                idEncodedLength += curId.getEncodedLength();
            }

            return idEncodedLength;
        }

        int getBindersEncodedLength() {
            int binderEncodedLength = 0;
            for (byte[] curBinder : binders) {
                binderEncodedLength += 1 + curBinder.length;
            }

            return binderEncodedLength;
        }

        byte[] getEncoded() throws IOException {
            int idsEncodedLength = getIdsEncodedLength();
            int bindersEncodedLength = getBindersEncodedLength();
            int encodedLength = 4 + idsEncodedLength + bindersEncodedLength;
            byte[] buffer = new byte[encodedLength];
            ByteBuffer m = ByteBuffer.wrap(buffer);
            Record.putInt16(m, idsEncodedLength);
            for(PskIdentity curId : identities) {
                curId.writeEncoded(m);
            }
            Record.putInt16(m, bindersEncodedLength);
            for (byte[] curBinder : binders) {
                Record.putBytes8(m, curBinder);
            }

            return buffer;
        }

        @Override
        public String toString() {
            MessageFormat messageFormat = new MessageFormat(
                "\"PreSharedKey\": '{'\n" +
                "  \"identities\"    : \"{0}\",\n" +
                "  \"binders\"       : \"{1}\",\n" +
                "'}'",
                Locale.ENGLISH);

            Object[] messageFields = {
                Utilities.indent(identitiesString()),
                Utilities.indent(bindersString())
            };

            return messageFormat.format(messageFields);
        }

        String identitiesString() {
            StringBuilder result = new StringBuilder();
            for(PskIdentity curId : identities) {
                result.append(curId.toString() + "\n");
            }

            return result.toString();
        }

        String bindersString() {
            StringBuilder result = new StringBuilder();
            for(byte[] curBinder : binders) {
                result.append("{" + Utilities.toHexString(curBinder) + "}\n");
            }

            return result.toString();
        }
    }

    private static final
            class CHPreSharedKeyStringizer implements SSLStringizer {
        @Override
        public String toString(ByteBuffer buffer) {
            try {
                // As the HandshakeContext parameter of CHPreSharedKeySpec
                // constructor is used for fatal alert only, we can use
                // null HandshakeContext here as we don't care about exception.
                //
                // Please take care of this code if the CHPreSharedKeySpec
                // constructor is updated in the future.
                return (new CHPreSharedKeySpec(null, buffer)).toString();
            } catch (Exception ex) {
                // For debug logging only, so please swallow exceptions.
                return ex.getMessage();
            }
        }
    }

    private static final
            class SHPreSharedKeySpec implements SSLExtensionSpec {
        final int selectedIdentity;

        SHPreSharedKeySpec(int selectedIdentity) {
            this.selectedIdentity = selectedIdentity;
        }

        SHPreSharedKeySpec(HandshakeContext context,
                ByteBuffer m) throws IOException {
            if (m.remaining() < 2) {
                context.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "Invalid pre_shared_key extension: " +
                        "insufficient selected_identity (length=" +
                        m.remaining() + ")");
            }
            this.selectedIdentity = Record.getInt16(m);
        }

        byte[] getEncoded() throws IOException {
            return new byte[] {
                (byte)((selectedIdentity >> 8) & 0xFF),
                (byte)(selectedIdentity & 0xFF)
            };
        }

        @Override
        public String toString() {
            MessageFormat messageFormat = new MessageFormat(
                "\"PreSharedKey\": '{'\n" +
                "  \"selected_identity\"      : \"{0}\",\n" +
                "'}'",
                Locale.ENGLISH);

            Object[] messageFields = {
                Utilities.byte16HexString(selectedIdentity)
            };

            return messageFormat.format(messageFields);
        }
    }

    private static final
            class SHPreSharedKeyStringizer implements SSLStringizer {
        @Override
        public String toString(ByteBuffer buffer) {
            try {
                // As the HandshakeContext parameter of SHPreSharedKeySpec
                // constructor is used for fatal alert only, we can use
                // null HandshakeContext here as we don't care about exception.
                //
                // Please take care of this code if the SHPreSharedKeySpec
                // constructor is updated in the future.
                return (new SHPreSharedKeySpec(null, buffer)).toString();
            } catch (Exception ex) {
                // For debug logging only, so please swallow exceptions.
                return ex.getMessage();
            }
        }
    }

    private static final
            class CHPreSharedKeyConsumer implements ExtensionConsumer {
        // Prevent instantiation of this class.
        private CHPreSharedKeyConsumer() {
            // blank
        }

        @Override
        public void consume(ConnectionContext context,
                            HandshakeMessage message,
                            ByteBuffer buffer) throws IOException {
            ServerHandshakeContext shc = (ServerHandshakeContext)context;
            // Is it a supported and enabled extension?
            if (!shc.sslConfig.isAvailable(SSLExtension.CH_PRE_SHARED_KEY)) {
                if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                    SSLLogger.fine(
                            "Ignore unavailable pre_shared_key extension");
                }
                return;     // ignore the extension
            }

            // Parse the extension.
            CHPreSharedKeySpec pskSpec = null;
            try {
                pskSpec = new CHPreSharedKeySpec(shc, buffer);
            } catch (IOException ioe) {
                shc.conContext.fatal(Alert.UNEXPECTED_MESSAGE, ioe);
                return;     // fatal() always throws, make the compiler happy.
            }

            // The "psk_key_exchange_modes" extension should have been loaded.
            if (!shc.handshakeExtensions.containsKey(
                    SSLExtension.PSK_KEY_EXCHANGE_MODES)) {
                shc.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "Client sent PSK but not PSK modes, or the PSK " +
                        "extension is not the last extension");
            }

            // error if id and binder lists are not the same length
            if (pskSpec.identities.size() != pskSpec.binders.size()) {
                shc.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                        "PSK extension has incorrect number of binders");
            }

            if (shc.isResumption) {     // resumingSession may not be set
                SSLSessionContextImpl sessionCache = (SSLSessionContextImpl)
                        shc.sslContext.engineGetServerSessionContext();
                int idIndex = 0;
                for (PskIdentity requestedId : pskSpec.identities) {
                    SSLSessionImpl s = sessionCache.get(requestedId.identity);
                    if (s != null && s.isRejoinable() &&
                            s.getPreSharedKey().isPresent()) {
                        if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                            SSLLogger.fine("Resuming session: ", s);
                        }

                        // binder will be checked later
                        shc.resumingSession = s;
                        shc.handshakeExtensions.put(SH_PRE_SHARED_KEY,
                            new SHPreSharedKeySpec(idIndex));   // for the index
                        break;
                    }

                    ++idIndex;
                }

                if (idIndex == pskSpec.identities.size()) {
                    // no resumable session
                    shc.isResumption = false;
                    shc.resumingSession = null;
                }
            }

            // update the context
            shc.handshakeExtensions.put(
                    SSLExtension.CH_PRE_SHARED_KEY, pskSpec);
        }
    }

    private static final
            class CHPreSharedKeyUpdate implements HandshakeConsumer {
        // Prevent instantiation of this class.
        private CHPreSharedKeyUpdate() {
            // blank
        }

        @Override
        public void consume(ConnectionContext context,
                HandshakeMessage message) throws IOException {
            ServerHandshakeContext shc = (ServerHandshakeContext)context;
            if (!shc.isResumption || shc.resumingSession == null) {
                // not resuming---nothing to do
                return;
            }

            CHPreSharedKeySpec chPsk = (CHPreSharedKeySpec)
                    shc.handshakeExtensions.get(SSLExtension.CH_PRE_SHARED_KEY);
            SHPreSharedKeySpec shPsk = (SHPreSharedKeySpec)
                    shc.handshakeExtensions.get(SSLExtension.SH_PRE_SHARED_KEY);
            if (chPsk == null || shPsk == null) {
                shc.conContext.fatal(Alert.INTERNAL_ERROR,
                        "Required extensions are unavailable");
            }

            byte[] binder = chPsk.binders.get(shPsk.selectedIdentity);

            // set up PSK binder hash
            HandshakeHash pskBinderHash = shc.handshakeHash.copy();
            byte[] lastMessage = pskBinderHash.removeLastReceived();
            ByteBuffer messageBuf = ByteBuffer.wrap(lastMessage);
            // skip the type and length
            messageBuf.position(4);
            // read to find the beginning of the binders
            ClientHelloMessage.readPartial(shc.conContext, messageBuf);
            int length = messageBuf.position();
            messageBuf.position(0);
            pskBinderHash.receive(messageBuf, length);

            checkBinder(shc, shc.resumingSession, pskBinderHash, binder);
        }
    }

    private static void checkBinder(ServerHandshakeContext shc,
            SSLSessionImpl session,
            HandshakeHash pskBinderHash, byte[] binder) throws IOException {
        Optional<SecretKey> pskOpt = session.getPreSharedKey();
        if (!pskOpt.isPresent()) {
            shc.conContext.fatal(Alert.INTERNAL_ERROR,
                    "Session has no PSK");
        }
        SecretKey psk = pskOpt.get();

        SecretKey binderKey = deriveBinderKey(psk, session);
        byte[] computedBinder =
                computeBinder(binderKey, session, pskBinderHash);
        if (!Arrays.equals(binder, computedBinder)) {
            shc.conContext.fatal(Alert.ILLEGAL_PARAMETER,
            "Incorect PSK binder value");
        }
    }

    // Class that produces partial messages used to compute binder hash
    static final class PartialClientHelloMessage extends HandshakeMessage {

        private final ClientHello.ClientHelloMessage msg;
        private final CHPreSharedKeySpec psk;

        PartialClientHelloMessage(HandshakeContext ctx,
                                  ClientHello.ClientHelloMessage msg,
                                  CHPreSharedKeySpec psk) {
            super(ctx);

            this.msg = msg;
            this.psk = psk;
        }

        @Override
        SSLHandshake handshakeType() {
            return msg.handshakeType();
        }

        private int pskTotalLength() {
            return psk.getIdsEncodedLength() +
                psk.getBindersEncodedLength() + 8;
        }

        @Override
        int messageLength() {

            if (msg.extensions.get(SSLExtension.CH_PRE_SHARED_KEY) != null) {
                return msg.messageLength();
            } else {
                return msg.messageLength() + pskTotalLength();
            }
        }

        @Override
        void send(HandshakeOutStream hos) throws IOException {
            msg.sendCore(hos);

            // complete extensions
            int extsLen = msg.extensions.length();
            if (msg.extensions.get(SSLExtension.CH_PRE_SHARED_KEY) == null) {
                extsLen += pskTotalLength();
            }
            hos.putInt16(extsLen - 2);
            // write the complete extensions
            for (SSLExtension ext : SSLExtension.values()) {
                byte[] extData = msg.extensions.get(ext);
                if (extData == null) {
                    continue;
                }
                // the PSK could be there from an earlier round
                if (ext == SSLExtension.CH_PRE_SHARED_KEY) {
                    continue;
                }
                int extID = ext.id;
                hos.putInt16(extID);
                hos.putBytes16(extData);
            }

            // partial PSK extension
            int extID = SSLExtension.CH_PRE_SHARED_KEY.id;
            hos.putInt16(extID);
            byte[] encodedPsk = psk.getEncoded();
            hos.putInt16(encodedPsk.length);
            hos.write(encodedPsk, 0, psk.getIdsEncodedLength() + 2);
        }
    }

    private static final
            class CHPreSharedKeyProducer implements HandshakeProducer {
        // Prevent instantiation of this class.
        private CHPreSharedKeyProducer() {
            // blank
        }

        @Override
        public byte[] produce(ConnectionContext context,
                HandshakeMessage message) throws IOException {

            // The producing happens in client side only.
            ClientHandshakeContext chc = (ClientHandshakeContext)context;
            if (!chc.isResumption || chc.resumingSession == null) {
                if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                    SSLLogger.fine("No session to resume.");
                }
                return null;
            }

            Optional<SecretKey> pskOpt = chc.resumingSession.getPreSharedKey();
            if (!pskOpt.isPresent()) {
                if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                    SSLLogger.fine("Existing session has no PSK.");
                }
                return null;
            }
            SecretKey psk = pskOpt.get();
            Optional<byte[]> pskIdOpt = chc.resumingSession.getPskIdentity();
            if (!pskIdOpt.isPresent()) {
                if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                    SSLLogger.fine(
                        "PSK has no identity, or identity was already used");
                }
                return null;
            }
            byte[] pskId = pskIdOpt.get();

            if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                SSLLogger.fine(
                    "Found resumable session. Preparing PSK message.");
            }

            List<PskIdentity> identities = new ArrayList<>();
            int ageMillis = (int)(System.currentTimeMillis() -
                    chc.resumingSession.getTicketCreationTime());
            int obfuscatedAge =
                    ageMillis + chc.resumingSession.getTicketAgeAdd();
            identities.add(new PskIdentity(pskId, obfuscatedAge));

            SecretKey binderKey = deriveBinderKey(psk, chc.resumingSession);
            ClientHelloMessage clientHello = (ClientHelloMessage)message;
            CHPreSharedKeySpec pskPrototype = createPskPrototype(
                chc.resumingSession.getSuite().hashAlg.hashLength, identities);
            HandshakeHash pskBinderHash = chc.handshakeHash.copy();

            byte[] binder = computeBinder(binderKey, pskBinderHash,
                    chc.resumingSession, chc, clientHello, pskPrototype);

            List<byte[]> binders = new ArrayList<>();
            binders.add(binder);

            CHPreSharedKeySpec pskMessage =
                    new CHPreSharedKeySpec(identities, binders);
            chc.handshakeExtensions.put(CH_PRE_SHARED_KEY, pskMessage);
            return pskMessage.getEncoded();
        }

        private CHPreSharedKeySpec createPskPrototype(
                int hashLength, List<PskIdentity> identities) {
            List<byte[]> binders = new ArrayList<>();
            byte[] binderProto = new byte[hashLength];
            for (PskIdentity curId : identities) {
                binders.add(binderProto);
            }

            return new CHPreSharedKeySpec(identities, binders);
        }
    }

    private static byte[] computeBinder(SecretKey binderKey,
            SSLSessionImpl session,
            HandshakeHash pskBinderHash) throws IOException {

        pskBinderHash.determine(
                session.getProtocolVersion(), session.getSuite());
        pskBinderHash.update();
        byte[] digest = pskBinderHash.digest();

        return computeBinder(binderKey, session, digest);
    }

    private static byte[] computeBinder(SecretKey binderKey,
            HandshakeHash hash, SSLSessionImpl session,
            HandshakeContext ctx, ClientHello.ClientHelloMessage hello,
            CHPreSharedKeySpec pskPrototype) throws IOException {

        PartialClientHelloMessage partialMsg =
                new PartialClientHelloMessage(ctx, hello, pskPrototype);

        SSLEngineOutputRecord record = new SSLEngineOutputRecord(hash);
        HandshakeOutStream hos = new HandshakeOutStream(record);
        partialMsg.write(hos);

        hash.determine(session.getProtocolVersion(), session.getSuite());
        hash.update();
        byte[] digest = hash.digest();

        return computeBinder(binderKey, session, digest);
    }

    private static byte[] computeBinder(SecretKey binderKey,
            SSLSessionImpl session, byte[] digest) throws IOException {
        try {
            CipherSuite.HashAlg hashAlg = session.getSuite().hashAlg;
            HKDF hkdf = new HKDF(hashAlg.name);
            byte[] label = ("tls13 finished").getBytes();
            byte[] hkdfInfo = SSLSecretDerivation.createHkdfInfo(
                    label, new byte[0], hashAlg.hashLength);
            SecretKey finishedKey = hkdf.expand(
                    binderKey, hkdfInfo, hashAlg.hashLength, "TlsBinderKey");

            String hmacAlg =
                "Hmac" + hashAlg.name.replace("-", "");
            try {
                Mac hmac = JsseJce.getMac(hmacAlg);
                hmac.init(finishedKey);
                return hmac.doFinal(digest);
            } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
                throw new IOException(ex);
            }
        } catch(GeneralSecurityException ex) {
            throw new IOException(ex);
        }
    }

    private static SecretKey deriveBinderKey(SecretKey psk,
            SSLSessionImpl session) throws IOException {
        try {
            CipherSuite.HashAlg hashAlg = session.getSuite().hashAlg;
            HKDF hkdf = new HKDF(hashAlg.name);
            byte[] zeros = new byte[hashAlg.hashLength];
            SecretKey earlySecret = hkdf.extract(zeros, psk, "TlsEarlySecret");

            byte[] label = ("tls13 res binder").getBytes();
            MessageDigest md = MessageDigest.getInstance(hashAlg.toString());;
            byte[] hkdfInfo = SSLSecretDerivation.createHkdfInfo(
                    label, md.digest(new byte[0]), hashAlg.hashLength);
            return hkdf.expand(earlySecret,
                    hkdfInfo, hashAlg.hashLength, "TlsBinderKey");
        } catch (GeneralSecurityException ex) {
            throw new IOException(ex);
        }
    }

    private static final
            class CHPreSharedKeyAbsence implements HandshakeAbsence {
        @Override
        public void absent(ConnectionContext context,
                           HandshakeMessage message) throws IOException {

            if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                SSLLogger.fine(
                "Handling pre_shared_key absence.");
            }

            ServerHandshakeContext shc = (ServerHandshakeContext)context;

            // Resumption is only determined by PSK, when enabled
            shc.resumingSession = null;
            shc.isResumption = false;
        }
    }

    private static final
            class SHPreSharedKeyConsumer implements ExtensionConsumer {
        // Prevent instantiation of this class.
        private SHPreSharedKeyConsumer() {
            // blank
        }

        @Override
        public void consume(ConnectionContext context,
            HandshakeMessage message, ByteBuffer buffer) throws IOException {
            // The consuming happens in client side only.
            ClientHandshakeContext chc = (ClientHandshakeContext)context;

            // Is it a response of the specific request?
            if (!chc.handshakeExtensions.containsKey(
                    SSLExtension.CH_PRE_SHARED_KEY)) {
                chc.conContext.fatal(Alert.UNEXPECTED_MESSAGE,
                    "Server sent unexpected pre_shared_key extension");
            }

            SHPreSharedKeySpec shPsk = new SHPreSharedKeySpec(chc, buffer);
            if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                SSLLogger.fine(
                    "Received pre_shared_key extension: ", shPsk);
            }

            // The PSK identity should not be reused, even if it is
            // not selected.
            chc.resumingSession.consumePskIdentity();

            if (shPsk.selectedIdentity != 0) {
                chc.conContext.fatal(Alert.ILLEGAL_PARAMETER,
                    "Selected identity index is not in correct range.");
            }

            if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                SSLLogger.fine(
                "Resuming session: ", chc.resumingSession);
            }

            // remove the session from the cache
            SSLSessionContextImpl sessionCache = (SSLSessionContextImpl)
                    chc.sslContext.engineGetClientSessionContext();
            sessionCache.remove(chc.resumingSession.getSessionId());
        }
    }

    private static final
            class SHPreSharedKeyAbsence implements HandshakeAbsence {
        @Override
        public void absent(ConnectionContext context,
                HandshakeMessage message) throws IOException {
            ClientHandshakeContext chc = (ClientHandshakeContext)context;

            if (SSLLogger.isOn && SSLLogger.isOn("ssl,handshake")) {
                SSLLogger.fine("Handling pre_shared_key absence.");
            }

            if (chc.handshakeExtensions.containsKey(
                    SSLExtension.CH_PRE_SHARED_KEY)) {
                // The PSK identity should not be reused, even if it is
                // not selected.
                chc.resumingSession.consumePskIdentity();
            }

            // The server refused to resume, or the client did not
            // request 1.3 resumption.
            chc.resumingSession = null;
            chc.isResumption = false;
        }
    }

    private static final
            class SHPreSharedKeyProducer implements HandshakeProducer {
        // Prevent instantiation of this class.
        private SHPreSharedKeyProducer() {
            // blank
        }

        @Override
        public byte[] produce(ConnectionContext context,
                HandshakeMessage message) throws IOException {
            ServerHandshakeContext shc = (ServerHandshakeContext)context;
            SHPreSharedKeySpec psk = (SHPreSharedKeySpec)
                    shc.handshakeExtensions.get(SH_PRE_SHARED_KEY);
            if (psk == null) {
                return null;
            }

            return psk.getEncoded();
        }
    }
}
