package org.whispersystems.signalservice.loki.protocol.sessionmanagement

import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.libsignal.loki.SessionResetProtocol
import org.whispersystems.libsignal.loki.SessionResetStatus
import org.whispersystems.libsignal.state.SignalProtocolStore
import org.whispersystems.libsignal.util.guava.Optional
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.database.LokiThreadDatabaseProtocol
import org.whispersystems.signalservice.loki.protocol.closedgroups.SharedSenderKeysDatabaseProtocol

public class SessionManagementProtocol(private val sessionResetImpl: SessionResetProtocol, private val sskDatabase: SharedSenderKeysDatabaseProtocol,
    private val delegate: SessionManagementProtocolDelegate) {

    // region Initialization
    companion object {

        public lateinit var shared: SessionManagementProtocol

        public fun configureIfNeeded(sessionResetImpl: SessionResetProtocol, sskDatabase: SharedSenderKeysDatabaseProtocol, delegate: SessionManagementProtocolDelegate) {
            if (::shared.isInitialized) { return; }
            shared = SessionManagementProtocol(sessionResetImpl, sskDatabase, delegate)
        }
    }
    // endregion

    // region Sending
    public fun shouldMessageUseFallbackEncryption(message: Any, publicKey: String, store: SignalProtocolStore): Boolean {
        if (sskDatabase.isSSKBasedClosedGroup(publicKey)) { return true } // We don't actually use fallback encryption but this indicates that we don't need a session
        if (message is SignalServiceDataMessage && message.preKeyBundle.isPresent) { return true; }
        val recipient = SignalProtocolAddress(publicKey, SignalServiceAddress.DEFAULT_DEVICE_ID)
        return !store.containsSession(recipient)
    }

    /**
     * Called after an end session message is sent.
     */
    public fun setSessionResetStatusToInProgressIfNeeded(recipient: SignalServiceAddress, eventListener: Optional<SignalServiceMessageSender.EventListener>) {
        val publicKey = recipient.number
        val sessionResetStatus = sessionResetImpl.getSessionResetStatus(publicKey)
        if (sessionResetStatus == SessionResetStatus.REQUEST_RECEIVED) { return }
        Log.d("Loki", "Starting session reset")
        sessionResetImpl.setSessionResetStatus(publicKey, SessionResetStatus.IN_PROGRESS)
        if (!eventListener.isPresent) { return }
        eventListener.get().onSecurityEvent(recipient)
    }

    public fun repairSessionIfNeeded(recipient: SignalServiceAddress, isClosedGroup: Boolean) {
        val publicKey = recipient.number
        if (!isClosedGroup) { return }
        delegate.sendSessionRequestIfNeeded(publicKey)
    }

    public fun shouldIgnoreMissingPreKeyBundleException(isClosedGroup: Boolean): Boolean {
        // When a closed group is created, members try to establish sessions with eachother in the background through
        // session requests. Until ALL users those session requests were sent to have come online, stored the pre key
        // bundles contained in the session requests and replied with background messages to finalize the session
        // creation, a given user won't be able to successfully send a message to all members of a group. This check
        // is so that until we can do better on this front the user at least won't see this as an error in the UI.
        return isClosedGroup
    }
    // endregion
}
