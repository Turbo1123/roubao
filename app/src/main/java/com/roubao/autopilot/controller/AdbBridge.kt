package com.roubao.autopilot.controller

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * 本机 ADB 桥 - 通过无线调试让 App 直连本机 adbd（与 dsh-go 的「自连 ADB」同一路线）
 *
 * 首次使用需在 设置 → 控制方式 → ADB 里完成一次配对（开发者选项 → 无线调试 →
 * 使用配对码配对设备），之后连接端口由 mDNS 自动发现，任务开始前自动重连。
 * 密钥对与自签证书保存在 App 私有目录，配对一次长期有效（除非用户在系统里撤销）。
 * 所有方法都是阻塞 IO，必须在后台线程调用。
 */
object AdbBridge {

    private var manager: SaakAdbManager? = null
    private var appContext: Context? = null

    @Volatile
    private var lastConnectAttemptMs = 0L

    fun init(context: Context) {
        if (manager == null) {
            appContext = context.applicationContext
            manager = try {
                SaakAdbManager(context.applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun isConnected(): Boolean {
        return try {
            manager?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }

    /** 使用配对码配对（端口与配对码来自系统无线调试的配对弹窗） */
    fun pair(port: Int, code: String): Boolean {
        val m = manager ?: return false
        return try {
            m.pair("127.0.0.1", port, code)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 强制尝试自动连接一次（mDNS 发现无线调试端口），任务开始与设置页使用 */
    fun connectNow(timeoutMs: Long = 15000): Boolean {
        val m = manager ?: return false
        val ctx = appContext ?: return false
        lastConnectAttemptMs = System.currentTimeMillis()
        return try {
            m.autoConnect(ctx, timeoutMs)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** 按指定端口连接（无线调试主页显示的端口，自动发现失败时兜底） */
    fun connect(port: Int): Boolean {
        val m = manager ?: return false
        lastConnectAttemptMs = System.currentTimeMillis()
        return try {
            m.connect("127.0.0.1", port)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        try {
            manager?.disconnect()
        } catch (e: Exception) {
            // 忽略断开异常
        }
    }

    /**
     * 确保连接可用：已连接直接返回；未连接时节流自动重连（20 秒最多试一次），
     * 供控制器在每条命令前调用，避免无线调试关闭时每条命令都长时间阻塞。
     */
    fun ensureConnected(): Boolean {
        if (isConnected()) {
            return true
        }
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptMs < 20000) {
            return false
        }
        return connectNow(10000)
    }

    /** 执行一条 shell 命令并返回文本输出 */
    fun exec(command: String, timeoutMs: Long = 20000): String {
        val bytes = execBytes(command, timeoutMs)
        return String(bytes, Charsets.UTF_8)
    }

    /** 执行一条 shell 命令并返回原始字节输出（截图等二进制场景） */
    fun execBytes(command: String, timeoutMs: Long = 15000): ByteArray {
        val m = manager ?: return ByteArray(0)
        if (!isConnected()) {
            return ByteArray(0)
        }
        return try {
            val stream: AdbStream = m.openStream("shell:$command")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                val input = stream.openInputStream()
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buffer)
                    if (n < 0) {
                        break
                    }
                    if (n == 0) {
                        Thread.sleep(10)
                        continue
                    }
                    out.write(buffer, 0, n)
                }
            } catch (e: Exception) {
                // 对端关闭流等情况视为读取结束
            } finally {
                try {
                    stream.close()
                } catch (e: Exception) {
                    // 忽略关闭异常
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }
    }
}

/**
 * ADB 连接管理器：生成并持久化本机的 RSA 密钥与自签证书（无线调试配对凭据）
 */
class SaakAdbManager(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "private.key")
        val certFile = File(dir, "cert.crt")
        if (keyFile.exists() && certFile.exists()) {
            val keyFactory = KeyFactory.getInstance("RSA")
            privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
        } else {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()
            privateKey = keyPair.private
            val now = System.currentTimeMillis()
            val certGen = X509V3CertificateGenerator()
            val subject = X500Principal("CN=Banyu")
            certGen.setSerialNumber(BigInteger.valueOf(now))
            certGen.setIssuerDN(subject)
            certGen.setSubjectDN(subject)
            certGen.setNotBefore(Date(now - 86400000L))
            certGen.setNotAfter(Date(now + 10L * 365 * 86400000L))
            certGen.setPublicKey(keyPair.public)
            certGen.setSignatureAlgorithm("SHA256WithRSAEncryption")
            certificate = certGen.generate(keyPair.private, "BC")
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Banyu"
}
