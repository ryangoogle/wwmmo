package au.com.codeka.warworlds.server.html.account

import au.com.codeka.warworlds.common.Log
import au.com.codeka.warworlds.common.proto.LoginRequest
import au.com.codeka.warworlds.common.proto.LoginResponse
import au.com.codeka.warworlds.server.handlers.ProtobufRequestHandler
import au.com.codeka.warworlds.server.net.ServerSocketManager
import au.com.codeka.warworlds.server.store.DataStore
import au.com.codeka.warworlds.server.world.EmpireManager
import com.google.common.base.Strings

/**
 * This servlet is posted to in order to "log in". You'll get a pointer to the socket to connect
 * to for the actual main game connection.
 */
class LoginHandler : ProtobufRequestHandler() {
  private val log = Log("LoginHandler")

  public override fun post() {
    val req = readProtobuf(LoginRequest::class.java)
    if (Strings.isNullOrEmpty(req.cookie)) {
      log.warning("No cookie in request, not connected.")
      response.status = 403
      return
    }
    log.info("Login request received, cookie=%s", req.cookie)

    val account = DataStore.i.accounts()[req.cookie]
    if (account == null) {
      log.warning("No account for cookie, not connecting: %s", req.cookie)
      response.status = 401
      return
    }

    val empire = EmpireManager.i.getEmpire(account.empire_id)
    if (empire == null) {
      log.warning("No empire with ID %d", account.empire_id)
      response.status = 404
      return
    }

    DataStore.i.empires().saveDevice(empire.get(), req.device_info)
    DataStore.i.stats().addLoginEvent(req, account)
    val resp = LoginResponse.Builder().status(LoginResponse.LoginStatus.SUCCESS)
    // Tell the server socket to expect a connection from this client.
    ServerSocketManager.Companion.i.addPendingConnection(account, empire, null /* encryptionKey */)
    resp.port(8081).empire(empire.get())
    writeProtobuf(resp.build())
  }
}