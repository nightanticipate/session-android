package org.thoughtcrime.securesms.loki

import android.content.Context
import android.os.Handler
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.concurrent.SettableFuture
import org.whispersystems.libsignal.util.guava.Optional
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.LokiStorageAPI
import org.whispersystems.signalservice.loki.api.PairingAuthorisation
import org.whispersystems.signalservice.loki.messaging.LokiThreadFriendRequestStatus
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded

fun getAllDeviceFriendRequestStatus(context: Context, hexEncodedPublicKey: String, storageAPI: LokiStorageAPI): Promise<Map<String, LokiThreadFriendRequestStatus>, Exception> {
  val lokiThreadDatabase = DatabaseFactory.getLokiThreadDatabase(context)
  return storageAPI.getAllDevicePublicKeys(hexEncodedPublicKey).map { keys ->
    val map = mutableMapOf<String, LokiThreadFriendRequestStatus>()

    for (devicePublicKey in keys) {
      val device = Recipient.from(context, Address.fromSerialized(devicePublicKey), false)
      val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(device)
      val friendRequestStatus = if (threadID < 0) LokiThreadFriendRequestStatus.NONE else lokiThreadDatabase.getFriendRequestStatus(threadID)
      map.put(devicePublicKey, friendRequestStatus);
    }

    map
  }
}

fun getAllDevicePublicKeys(context: Context, hexEncodedPublicKey: String, storageAPI: LokiStorageAPI, block: (devicePublicKey: String, isFriend: Boolean, friendCount: Int) -> Unit) {
  val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
  val devices = getAllDevicePublicKeys(hexEncodedPublicKey, storageAPI).toMutableSet()
  if (hexEncodedPublicKey != userHexEncodedPublicKey) {
    devices.remove(userHexEncodedPublicKey)
  }
  val friends = getFriendPublicKeys(context, devices)
  for (device in devices) {
    block(device, friends.contains(device), friends.count())
  }
}

fun getAllDevicePublicKeys(hexEncodedPublicKey: String, storageAPI: LokiStorageAPI): Set<String> {
  val future = SettableFuture<Set<String>>()
  storageAPI.getAllDevicePublicKeys(hexEncodedPublicKey).success { future.set(it) }.fail { future.setException(it) }
  return try {
    future.get()
  } catch (e: Exception) {
    setOf()
  }
}

fun shouldAutomaticallyBecomeFriendsWithDevice(publicKey: String, context: Context): Boolean {
  val lokiThreadDatabase = DatabaseFactory.getLokiThreadDatabase(context)
  val storageAPI = LokiStorageAPI.shared
  val future = SettableFuture<Boolean>()
  storageAPI.getPrimaryDevicePublicKey(publicKey).success { primaryDevicePublicKey ->
    if (primaryDevicePublicKey == null) {
      // If the public key doesn't have any other devices then go through regular friend request logic
      future.set(false)
      return@success
    }
    // If we are the primary device and the public key is our secondary device then we should become friends
    val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(context)
    if (primaryDevicePublicKey == userHexEncodedPublicKey) {
      storageAPI.getSecondaryDevicePublicKeys(userHexEncodedPublicKey).success { secondaryDevices ->
        future.set(secondaryDevices.contains(publicKey))
      }.fail {
        future.set(false)
      }
      return@success
    }
    // If we share the same primary device then we should become friends
    val ourPrimaryDevice = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
    if (ourPrimaryDevice != null && ourPrimaryDevice == primaryDevicePublicKey) {
      future.set(true)
      return@success
    }
    // If we are friends with the primary device then we should become friends
    val primaryDevice = Recipient.from(context, Address.fromSerialized(primaryDevicePublicKey), false)
    val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(primaryDevice)
    if (threadID < 0) {
      future.set(false)
      return@success
    }
    future.set(lokiThreadDatabase.getFriendRequestStatus(threadID) == LokiThreadFriendRequestStatus.FRIENDS)
  }

  return try {
    future.get()
  } catch (e: Exception) {
    false
  }
}

fun sendPairingAuthorisationMessage(context: Context, contactHexEncodedPublicKey: String, authorisation: PairingAuthorisation): Promise<Unit, Exception> {
  val messageSender = ApplicationContext.getInstance(context).communicationModule.provideSignalMessageSender()
  val address = SignalServiceAddress(contactHexEncodedPublicKey)
  val message = SignalServiceDataMessage.newBuilder().withBody("").withPairingAuthorisation(authorisation)
  // A REQUEST should always act as a friend request. A GRANT should always be replying back as a normal message.
  if (authorisation.type == PairingAuthorisation.Type.REQUEST) {
    val preKeyBundle = DatabaseFactory.getLokiPreKeyBundleDatabase(context).generatePreKeyBundle(address.number)
    message.asFriendRequest(true).withPreKeyBundle(preKeyBundle)
  }
  return try {
    Log.d("Loki", "Sending authorisation message to: $contactHexEncodedPublicKey.")
    val result = messageSender.sendMessage(0, address, Optional.absent<UnidentifiedAccessPair>(), message.build())
    if (result.success == null) {
      val exception = when {
        result.isNetworkFailure -> "Failed to send authorisation message due to a network error."
        else -> "Failed to send authorisation message."
      }
      throw Exception(exception)
    }
    Promise.ofSuccess(Unit)
  } catch (e: Exception) {
    Log.d("Loki", "Failed to send authorisation message to: $contactHexEncodedPublicKey.")
    Promise.ofFail(e)
  }
}

fun signAndSendPairingAuthorisationMessage(context: Context, pairingAuthorisation: PairingAuthorisation) {
  val userPrivateKey = IdentityKeyUtil.getIdentityKeyPair(context).privateKey.serialize()
  val signedPairingAuthorisation = pairingAuthorisation.sign(PairingAuthorisation.Type.GRANT, userPrivateKey)
  if (signedPairingAuthorisation == null || signedPairingAuthorisation.type != PairingAuthorisation.Type.GRANT) {
    Log.d("Loki", "Failed to sign pairing authorization.")
    return
  }
  retryIfNeeded(8) {
    sendPairingAuthorisationMessage(context, pairingAuthorisation.secondaryDevicePublicKey, signedPairingAuthorisation).get()
  }.fail {
    Log.d("Loki", "Failed to send pairing authorization message to ${pairingAuthorisation.secondaryDevicePublicKey}.")
  }
  DatabaseFactory.getLokiAPIDatabase(context).insertOrUpdatePairingAuthorisation(signedPairingAuthorisation)
  // Call function after a short delay
  Handler().postDelayed({
    LokiStorageAPI.shared.updateUserDeviceMappings().fail {
      Log.w("Loki", "Failed to update device mapping")
    }
  }, 100)

}

fun shouldSendSycMessage(context: Context, address: Address): Boolean {
  if (address.isGroup || address.isEmail || address.isMmsGroup) {
    return false
  }

  // Don't send sync messages if it's our address
  val publicKey = address.serialize()
  if (publicKey == TextSecurePreferences.getLocalNumber(context)) {
    return false
  }

  val storageAPI = LokiStorageAPI.shared
  val future = SettableFuture<Boolean>()
  storageAPI.getPrimaryDevicePublicKey(publicKey).success { primaryDevicePublicKey ->
    val isOurPrimaryDevice = primaryDevicePublicKey != null && TextSecurePreferences.getMasterHexEncodedPublicKey(context) == publicKey
    // Don't send sync message if the primary device is the same as ours
    future.set(!isOurPrimaryDevice)
  }.fail {
    future.set(false)
  }

  return try {
    future.get()
  } catch (e: Exception) {
    false
  }
}

fun isOneOfOurDevices(context: Context, address: Address): Boolean {
  if (address.isGroup || address.isEmail || address.isMmsGroup) {
    return false
  }

  val ourPublicKey = TextSecurePreferences.getLocalNumber(context)
  val storageAPI = LokiStorageAPI.shared
  val future = SettableFuture<Boolean>()
  storageAPI.getAllDevicePublicKeys(ourPublicKey).success {
    future.set(it.contains(address.serialize()))
  }.fail {
    future.set(false)
  }

  return try {
    future.get()
  } catch (e: Exception) {
    false
  }
}

fun getPrimaryDevicePublicKey(hexEncodedPublicKey: String): String? {
  val storageAPI = LokiStorageAPI.shared
  val future = SettableFuture<String?>()
  storageAPI.getPrimaryDevicePublicKey(hexEncodedPublicKey).success {
    future.set(it)
  }.fail {
    future.set(null)
  }

  return try {
    future.get()
  } catch (e: Exception) {
    null
  }
}

fun isFriendsWithAnyLinkedDevice(context: Context, recipient: Recipient): Boolean {
  if (recipient.isGroupRecipient) return true
  val future = SettableFuture<Boolean>()
  val storageAPI = LokiStorageAPI.shared

  getAllDeviceFriendRequestStatus(context, recipient.address.serialize(), storageAPI).success { map ->
    for (status in map.values) {
      if (status == LokiThreadFriendRequestStatus.FRIENDS) {
        future.set(true)
        break
      }
    }

    if (!future.isDone) {
      future.set(false)
    }
  }.fail { e ->
    future.set(false)
  }

  try {
    return future.get()
  } catch (e: Exception) {
    return false
  }

}