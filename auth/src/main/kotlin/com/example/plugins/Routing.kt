package com.example.plugins

import com.auth0.jwk.*
import com.auth0.jwt.*
import com.auth0.jwt.algorithms.*
import com.example.domain.entity.*
import com.example.domain.entity.UserToken.Companion.DAY_MILLIS
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import java.util.concurrent.TimeUnit
import com.example.server.LocalApi
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.json.buildJsonObject
import org.json.simple.JSONObject
import java.sql.Timestamp

@kotlinx.serialization.Serializable
data class YandexTokenRequest(
    val grant_type: String,
    val code: String
        )
fun Application.configureRouting(applicationHttpClient: HttpClient) {
    routing {
        // Применяю OAuth авторизацию к запросам ниже
        authenticate("auth-oauth-yandex") {
            // Используется клиентом, чтобы зарегистрироваться/войти
            // Далее он будет жить на jwt
            // Если jwt иссякнет, то опять /login
            get("/authorize") {
                // Редирект на страницу Яндекса
            }
        }
        get("/token") {
            // Получаю код из параметра
            val code = call.parameters["code"]

            val httpResponse = applicationHttpClient.post("https://oauth.yandex.ru/token"){
                headers {
                    append(HttpHeaders.Authorization, "Basic ${Base64.getEncoder().encodeToString(
                        "${System.getenv()["YANDEX_CLIENT_ID"]}:${System.getenv()["YANDEX_CLIENT_SECRET"]}".toByteArray())}")
                    append(HttpHeaders.ContentType,ContentType.Application.FormUrlEncoded)
                }

                val parameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code!!)
                }

                setBody(
                    TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded)
                )
            }

            if (httpResponse.status != HttpStatusCode.OK){
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val principal: YandexPrincipal = httpResponse.body()

            // Заправшиваю информацию от Яндекса
            val userInfo: YandexUser = applicationHttpClient.get("https://login.yandex.ru/info?format=json") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${principal.accessToken}")
                }
            }.body()
            val user = LocalApi.getWithYandex(userInfo.id.toLong())
            val resultId: Long?
            if (user == null){
                // Регистрация
                resultId = LocalApi.addUser(User(
                    displayName = userInfo.displayName,
                    yandexId = userInfo.id.toLong(),
                    accessTokenYandex = principal.accessToken!!,
                    refreshTokenYandex = principal.refreshToken!!
                ))?.id
            }else{
                // Авторизация
                resultId = LocalApi.updateUser(user.copy(
                    displayName = userInfo.displayName,
                    accessTokenYandex = principal.accessToken!!,
                    refreshTokenYandex = principal.refreshToken!!
                ))?.id
            }
            // Получение id нового утсройства
            val deviceId = LocalApi.getNewDeviceIdForUser(resultId!!)
            val tokenPair = generateJWT(this@configureRouting.environment, resultId, deviceId)
            LocalApi.addUserToken(UserToken(
                deviceId = deviceId,
                id = resultId,
                accessToken = tokenPair.accessToken,
                refreshToken = tokenPair.refreshToken,
                refreshTokenExpireAt = System.currentTimeMillis() + DAY_MILLIS
            ))
            call.respond(tokenPair)
        }

        post("/refresh") {
            val oldRefreshToken = call.receive<RefreshToken>().refresh_token
            val userToken = LocalApi.getUserTokenWithRefreshToken(oldRefreshToken)
            if (userToken != null){
                if(userToken.refreshTokenExpireAt > System.currentTimeMillis()){
                    // Токен действителен
                    val tokenPair = generateJWT(this@configureRouting.environment, userToken.id, userToken.deviceId)
                    val res = LocalApi.updateUserToken(UserToken(
                        deviceId = userToken.deviceId,
                        id =  userToken.id,
                        accessToken = tokenPair.accessToken,
                        refreshToken = tokenPair.refreshToken,
                        refreshTokenExpireAt = tokenPair.expiresAt
                    ))
                    call.respond(res!!)
                }else{
                    // Токен просрочен
                    call.respond(HttpStatusCode.BadRequest)
                }
            }else{
                // Токен не существует
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        authenticate("auth-jwt") {
            post("/logout") {
                val principal = call.principal<JWTPrincipal>()
                val (userId,deviceId) = getUserInfo(principal)
                LocalApi.removeUserToken(userId.toLong(),deviceId.toLong())
                call.respond(HttpStatusCode.OK)
            }
        }

        // Делаю папку certs статикой
        static(".well-known") {
            staticRootFolder = File("certs")
            file("jwks.json")
        }
    }
}

fun generateJWT(environment: ApplicationEnvironment, userId: Long, deviceId: Long): TokenPair {
    // Приватный ключ RSA
    val privateKeyString = environment.config.property("jwt.access.privateKey").getString()
    // Изготовитель токена
    val issuer = environment.config.property("jwt.issuer").getString()
    // Для кого предназначен токен
    val audience = environment.config.property("jwt.audience").getString()
    // Время жизни access токена в минутах
    val accessLifeTime = environment.config.property("jwt.access.lifetime").getString().toInt()

    val refreshLifeTime = environment.config.property("jwt.refresh.lifetime").getString().toInt()
    // Получаю доступ к JWKS, который хранит публичные ключи
    // jwks.json является статическим файлом, чтобы другие пользователи могли использовать эти публичные ключи,
    // для проверки токена на валидность
    // указываю URI, где находится .well-known/jwks.json

    val jwkProvider = JwkProviderBuilder(issuer)
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val publicKey = jwkProvider.get("test_key").publicKey
    val keySpecPKCS8 = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyString))
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpecPKCS8)
    val accessExpiresAt = Date(System.currentTimeMillis() + 1000*60*accessLifeTime)
    val refreshExpiresAt = Date(System.currentTimeMillis() + refreshLifeTime * DAY_MILLIS)
    val accessToken = JWT.create()
        .withAudience(audience)
        .withIssuer("${issuer}")
        .withClaim("userId", userId)
        .withClaim("deviceId", deviceId)
        .withExpiresAt(accessExpiresAt)
        .sign(Algorithm.RSA256(publicKey as RSAPublicKey, privateKey as RSAPrivateKey))
    val refreshToken = UUID.randomUUID().toString()
    return TokenPair(accessToken, refreshToken, refreshExpiresAt.time)
}
@kotlinx.serialization.Serializable
data class TokenPair(val accessToken: String, val refreshToken: String, val expiresAt: Long)

@kotlinx.serialization.Serializable
data class RefreshToken(val refresh_token: String)

data class UserInfo(val userId: String,val deviceId: String)

private suspend fun getUserInfo(principal: JWTPrincipal?): UserInfo{
    val userId = principal!!.payload.getClaim("userId").asString()
    val deviceId = principal.payload.getClaim("deviceId").asString()
    return UserInfo(userId,deviceId)
}