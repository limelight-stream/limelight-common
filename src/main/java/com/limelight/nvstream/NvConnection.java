package com.limelight.nvstream;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer.VideoFormat;
import com.limelight.nvstream.http.GfeHttpResponseException;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;

public class NvConnection {
	// Context parameters
	private String host;
	private LimelightCryptoProvider cryptoProvider;
	private String uniqueId;
	private ConnectionContext context;
	
	public NvConnection(String host, String uniqueId, NvConnectionListener listener, StreamConfiguration config, LimelightCryptoProvider cryptoProvider)
	{		
		this.host = host;
		this.cryptoProvider = cryptoProvider;
		this.uniqueId = uniqueId;
		
		this.context = new ConnectionContext();
		this.context.connListener = listener;
		this.context.streamConfig = config;
		try {
			// This is unique per connection
			this.context.riKey = generateRiAesKey();
		} catch (NoSuchAlgorithmException e) {
			// Should never happen
			e.printStackTrace();
		}
		
		this.context.riKeyId = generateRiKeyId();
		
		this.context.negotiatedVideoFormat = VideoFormat.Unknown;
	}
	
	private static SecretKey generateRiAesKey() throws NoSuchAlgorithmException {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		
		// RI keys are 128 bits
		keyGen.init(128);
		
		return keyGen.generateKey();
	}
	
	private static int generateRiKeyId() {
		return new SecureRandom().nextInt();
	}
	
	public void stop()
	{

	}
	
	private boolean startApp() throws XmlPullParserException, IOException
	{
		NvHTTP h = new NvHTTP(context.serverAddress, uniqueId, null, cryptoProvider);
		
		String serverInfo = h.getServerInfo();
		
		context.serverAppVersion = h.getServerAppVersionQuad(serverInfo);
		if (context.serverAppVersion == null) {
			context.connListener.displayMessage("Server version malformed");
			return false;
		}

		int majorVersion = context.serverAppVersion[0];
		LimeLog.info("Server major version: "+majorVersion);
		
		if (majorVersion == 0) {
			context.connListener.displayMessage("Server version malformed");
			return false;
		}
		else if (majorVersion < 3) {
			// Even though we support major version 3 (2.1.x), GFE 2.2.2 is preferred.
			context.connListener.displayMessage("This app requires GeForce Experience 2.2.2 or later. Please upgrade GFE on your PC and try again.");
			return false;
		}
		else if (majorVersion > 7) {
			// Warn the user but allow them to continue
			context.connListener.displayTransientMessage("This version of GFE is not currently supported. You may experience issues until this app is updated.");
		}
		
		switch (majorVersion) {
		case 3:
			context.serverGeneration = ConnectionContext.SERVER_GENERATION_3;
			break;
		case 4:
			context.serverGeneration = ConnectionContext.SERVER_GENERATION_4;
			break;
		case 5:
			context.serverGeneration = ConnectionContext.SERVER_GENERATION_5;
			break;
		case 6:
			context.serverGeneration = ConnectionContext.SERVER_GENERATION_6;
			break;
		case 7:
		default:
			context.serverGeneration = ConnectionContext.SERVER_GENERATION_7;
			break;
		}
				
		if (h.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
			context.connListener.displayMessage("Device not paired with computer");
			return false;
		}
		
		//
		// Decide on negotiated stream parameters now
		//
		
		// Check for a supported stream resolution
		if (context.streamConfig.getHeight() >= 2160 && !h.supports4K(serverInfo)) {
			// Client wants 4K but the server can't do it
			context.connListener.displayTransientMessage("Your PC does not have a supported GPU or GFE version for 4K streaming. The stream will be 1080p.");
			
			// Lower resolution to 1080p
			context.negotiatedWidth = 1920;
			context.negotiatedHeight = 1080;
			context.negotiatedFps = context.streamConfig.getRefreshRate();
		}
		else if (context.streamConfig.getHeight() >= 2160 && context.streamConfig.getRefreshRate() >= 60 && !h.supports4K60(serverInfo)) {
			// Client wants 4K 60 FPS but the server can't do it
			context.connListener.displayTransientMessage("Your GPU does not support 4K 60 FPS streaming. The stream will be 4K 30 FPS.");
			
			context.negotiatedWidth = context.streamConfig.getWidth();
			context.negotiatedHeight = context.streamConfig.getHeight();
			context.negotiatedFps = 30;
		}
		else {
			// Take what the client wanted
			context.negotiatedWidth = context.streamConfig.getWidth();
			context.negotiatedHeight = context.streamConfig.getHeight();
			context.negotiatedFps = context.streamConfig.getRefreshRate();
		}
		
		//
		// Video stream format will be decided during the RTSP handshake
		//
		
		NvApp app = context.streamConfig.getApp();
		
		// If the client did not provide an exact app ID, do a lookup with the applist
		if (!context.streamConfig.getApp().isInitialized()) {
			LimeLog.info("Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead");
			app = h.getAppByName(context.streamConfig.getApp().getAppName());
			if (app == null) {
				context.connListener.displayMessage("The app " + context.streamConfig.getApp().getAppName() + " is not in GFE app list");
				return false;
			}
		}
		
		// If there's a game running, resume it
		if (h.getCurrentGame(serverInfo) != 0) {
			try {
				if (h.getCurrentGame(serverInfo) == app.getAppId()) {
					if (!h.resumeApp(context)) {
						context.connListener.displayMessage("Failed to resume existing session");
						return false;
					}
				} else {
					return quitAndLaunch(h, app);
				}
			} catch (GfeHttpResponseException e) {
				if (e.getErrorCode() == 470) {
					// This is the error you get when you try to resume a session that's not yours.
					// Because this is fairly common, we'll display a more detailed message.
					context.connListener.displayMessage("This session wasn't started by this device," +
							" so it cannot be resumed. End streaming on the original " +
							"device or the PC itself and try again. (Error code: "+e.getErrorCode()+")");
					return false;
				}
				else if (e.getErrorCode() == 525) {
					context.connListener.displayMessage("The application is minimized. Resume it on the PC manually or " +
							"quit the session and start streaming again.");
					return false;
				} else {
					throw e;
				}
			}
			
			LimeLog.info("Resumed existing game session");
			return true;
		}
		else {
			return launchNotRunningApp(h, app);
		}
	}

	protected boolean quitAndLaunch(NvHTTP h, NvApp app) throws IOException,
			XmlPullParserException {
		try {
			if (!h.quitApp()) {
				context.connListener.displayMessage("Failed to quit previous session! You must quit it manually");
				return false;
			} 
		} catch (GfeHttpResponseException e) {
			if (e.getErrorCode() == 599) {
				context.connListener.displayMessage("This session wasn't started by this device," +
						" so it cannot be quit. End streaming on the original " +
						"device or the PC itself. (Error code: "+e.getErrorCode()+")");
				return false;
			}
			else {
				throw e;
			}
		}

		return launchNotRunningApp(h, app);
	}
	
	private boolean launchNotRunningApp(NvHTTP h, NvApp app) 
			throws IOException, XmlPullParserException {
		// Launch the app since it's not running
		if (!h.launchApp(context, app.getAppId())) {
			context.connListener.displayMessage("Failed to launch application");
			return false;
		}
		
		LimeLog.info("Launched new game session");
		
		return true;
	}
	
	private void establishConnection() {
		String appName = context.streamConfig.getApp().getAppName();

		context.connListener.stageStarting(appName);

		try {
			startApp();
			context.connListener.stageComplete(appName);
		} catch (Exception e) {
			e.printStackTrace();
			context.connListener.displayMessage(e.getMessage());
			context.connListener.stageFailed(appName);
			return;
		}
	}

	public void start(AudioRenderer audioRenderer, VideoDecoderRenderer videoDecoderRenderer)
	{
		this.context.videoDecoderRenderer = videoDecoderRenderer;
		
		new Thread(new Runnable() {
			public void run() {
				try {
					context.serverAddress = InetAddress.getByName(host);
				} catch (UnknownHostException e) {
					context.connListener.connectionTerminated(e);
					return;
				}
				
				establishConnection();
			}
		}).start();
	}
	
	public void sendMouseMove(final short deltaX, final short deltaY)
	{

	}
	
	public void sendMouseButtonDown(final byte mouseButton)
	{

	}
	
	public void sendMouseButtonUp(final byte mouseButton)
	{

	}
	
	public void sendControllerInput(final short controllerNumber,
			final short activeGamepadMask, final short buttonFlags,
			final byte leftTrigger, final byte rightTrigger,
			final short leftStickX, final short leftStickY,
			final short rightStickX, final short rightStickY)
	{

	}
	
	public void sendControllerInput(final short buttonFlags,
			final byte leftTrigger, final byte rightTrigger,
			final short leftStickX, final short leftStickY,
			final short rightStickX, final short rightStickY)
	{

	}
	
	public void sendKeyboardInput(final short keyMap, final byte keyDirection, final byte modifier) {

	}
	
	public void sendMouseScroll(final byte scrollClicks) {

	}
	
	public VideoFormat getActiveVideoFormat() {
		return context.negotiatedVideoFormat;
	}
}