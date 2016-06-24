package server;

import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.Timer;

public class Server extends JFrame implements ActionListener {

	// RTP variables
	DatagramSocket RTPsocket; // Socket to be used to send and receive UDP packets
	DatagramPacket senddp; // UDP packet containing the video frames

	InetAddress ClientIPAddr; // Client IP address
	int RTP_dest_port = 0; // Destination port for RTP packets (given by the RTSP Client)

	// GUI element however server dont'has a proper GUI
	JLabel label;

	// Video variables:
	int imagenb = 0; // Image number of the image currently transmitted
	VideoStream video; // VideoStream object used to access video frames
	static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video
	static int FRAME_PERIOD = 100; // Frame period of the video to stream, in ms
	static int VIDEO_LENGTH = 500; // length of the video in frames

	Timer timer; // timer used to send the images at the video frame rate
	byte[] buf; // buffer used to store the images to send to the client

	// RTSP variables
	// RTSP states
	final static int INIT = 0;
	final static int READY = 1;
	final static int PLAYING = 2;
	// RTSP message types
	final static int SETUP = 3;
	final static int PLAY = 4;
	final static int PAUSE = 5;
	final static int TEARDOWN = 6;

	static int state; // RTSP Server state == INIT or READY or PLAY
	
	// This socket will be created when a client connects. In order to communicate with the client
	// in a separate socket. For each client, the server will make a socket so it can communicate
	// to each client with another socket.
	Socket RTSPsocket; // Socket used to send/receive RTSP messages
	// Input and output stream filters
	static BufferedReader RTSPBufferedReader;
	static BufferedWriter RTSPBufferedWriter;
	static String VideoFileName; // Video file requested from the client
	static int RTSP_ID = 123456; // ID of the RTSP session
	int RTSPSeqNb = 0; // Sequence number of RTSP messages within the session

	final static String CRLF = "\r\n";
	
	long test = 0;

	/*
	 * Constructor
	 */
	public Server() {
		// Creates invisible frame with Server as its title
		super("Server");

		// Initialize Timer. Each frame lasts for 100ms, so every 100ms a frame
		// needs to be send. This means that when we have 500 frames total which all lasts for
		// 100ms, the total video will last 50 seconds.
		timer = new Timer(FRAME_PERIOD, this);
		timer.setInitialDelay(0); // No initial delay when the video starts
		// It is possible that multiple frames are queued. To avoid that each frame is then
		// send with no delay between them, coalescing is set to true to avoid this problem.
		timer.setCoalesce(true);

		// Allocate memory for the sending buffer
		buf = new byte[15000];

		// Handler to close the main window
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				// Stop the timer and exit
				timer.stop();
				System.exit(0);
			}
		});

		// GUI:
		label = new JLabel("Send frame #", JLabel.CENTER);
		getContentPane().add(label, BorderLayout.CENTER);
	}

	/*
	 *  The main method which will be invoked with arguments by the
	 *  command line.
	 */
	public static void main(String argv[]) throws Exception {
		// Create a Server object
		Server theServer = new Server();

		// Show GUI:
		theServer.pack(); //Resize window so that alle GUI elements take as much space as they need
		theServer.setVisible(true);

		// Get RTSP socket port from the command line
		int RTSPport = 4444;// Integer.parseInt(argv[0]);

		// Initiate TCP connection with the client for the RTSP session
		ServerSocket listenSocket = new ServerSocket(RTSPport);
		
		// Code beneath here will only be executed when a client connects
		theServer.RTSPsocket = listenSocket.accept();
		System.out.println("THE RTSP SOCKET SPECIAL FOR THE CLIENT IS: " + theServer.RTSPsocket.getPort());
		listenSocket.close();

		// Get Client IP address
		theServer.ClientIPAddr = theServer.RTSPsocket.getInetAddress();

		// Initiate RTSPstate
		state = INIT;

		// Set input and output stream filters:
		RTSPBufferedReader = new BufferedReader(new InputStreamReader(theServer.RTSPsocket.getInputStream()));
		RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theServer.RTSPsocket.getOutputStream()));

		// Wait for the SETUP message from the client
		int request_type;
		boolean done = false;
		// This loop keeps looping until the client has finally clicked on the SETUP button
		while (!done) {
			// Blocking (won't execute further until a request comes in) by the Readline() method in 
			// the parse_RTSP_request() method
			request_type = theServer.parse_RTSP_request(); 

			if (request_type == SETUP) {
				done = true;

				// Update RTSP state
				state = READY;
				System.out.println("New RTSP state: READY");

				// Send response
				theServer.send_RTSP_response();

				// Init the VideoStream object:
				theServer.video = new VideoStream(VideoFileName);

				// Init RTP socket with a random port number
				theServer.RTPsocket = new DatagramSocket();
				System.out.println("SERVER MADE RTP SOCKET WITH PORT NUMBER: " + theServer.RTPsocket.getPort());
			}
		}

		// Loop to handle RTSP requests
		while (true) {
			// Blocking (won't execute further until a request comes in) by the Readline() method in 
			// the parse_RTSP_request() method
			request_type = theServer.parse_RTSP_request();

			if ((request_type == PLAY) && (state == READY)) {
				// Send back response
				theServer.send_RTSP_response();
				// Start timer causing it to send action events to listeners (actionPerformed method).
				// So each 100ms events will be triggered.
				theServer.timer.start();
				// Update state
				state = PLAYING;
				System.out.println("New RTSP state: PLAYING");
			} else if ((request_type == PAUSE) && (state == PLAYING)) {
				// send back response
				theServer.send_RTSP_response();
				// stop timer
				theServer.timer.stop();
				// update state
				state = READY;
				System.out.println("New RTSP state: READY");
			} else if (request_type == TEARDOWN) {
				// send back response
				theServer.send_RTSP_response();
				// stop timer
				theServer.timer.stop();
				// close sockets
				theServer.RTSPsocket.close();
				theServer.RTPsocket.close();

				System.exit(0);
			}
		}
	}

	/*
	 * This method will be invoked every 100ms by the timer
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	public void actionPerformed(ActionEvent e) {

		// If the current image number is less than the total amount of images (frames) of the video (500)
		if (imagenb < VIDEO_LENGTH) {
			// Update current image number
			imagenb++;

			try {
				// Get next frame to send from the video, as well as its size
				int image_length = video.getnextframe(buf);
				//System.out.print("\n" + image_length);
				
				// Builds an RTPpacket object containing the frame
				// Arguments: the payload type (26), sequence number, timestamp (i.e. number 4 * 100ms = 400ms), the frame itself and the length of the data
				//RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb * FRAME_PERIOD, buf, image_length);
				
				//#### NEW ####
				int timeStamp = ((int)(new Date()).getTime());
				//int milis = Calendar.getInstance().get(Calendar.MILLISECOND);
				
				//int tsLong = (int)System.currentTimeMillis()/1000;
				RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, timeStamp, buf, image_length);
			
				System.out.println("Timestamp: " + timeStamp);
				test++;
				//#### NEW ####

				// Get to total length of the full rtp packet to send (so data + headers)
				int packet_length = rtp_packet.getlength();

				// Retrieve the packet bitstream and store it in an array of bytes
				byte[] packet_bits = new byte[packet_length];
				rtp_packet.getpacket(packet_bits);

				// Send the packet as a DatagramPacket over the UDP socket.
				// The RTP destination port was specified by the client, in this case 25 000 and is extracted
				// in the parse_RTSP_request() method.
				senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port); 
				//System.out.print("RTP_dest_port =" + RTP_dest_port + "\n");

				RTPsocket.send(senddp); //send the packet over the RTP socket of the server to the client's socket
				rtp_packet.printheader();

				// Update GUI
				label.setText("Send frame #" + imagenb);
			} catch (Exception ex) {
				System.out.println("Exception caught: " + ex);
				System.exit(0);
			}
		} else {
			// If we have reached the end of the video file, stop the timer
			timer.stop();
		}
	}

	/*
	 * Will read the header of the RTSP request made by the client and will
	 * filter the request type out of the headers, this can be: SETUP, PLAY, PAUSE or TEARDOWN.
	 * The request type will be returned.
	 */
	private int parse_RTSP_request() {
		int request_type = -1;
		try {
			// Read the first line of the header send by the client
			// which for a setup request is something like: SETUP movie.Mjpeg RTSP/1.0
			String RequestLine = RTSPBufferedReader.readLine(); // Will only read when a message comes in via the RTSP sockets inputstream
			System.out.println(RequestLine);

			StringTokenizer tokens = new StringTokenizer(RequestLine); // Will split the sentence based on spaces
			String request_type_string = tokens.nextToken(); //Get the first word of the first line (SETUP)

			// Convert to request_type structure:
			if ((new String(request_type_string)).compareTo("SETUP") == 0)
				request_type = SETUP;
			else if ((new String(request_type_string)).compareTo("PLAY") == 0)
				request_type = PLAY;
			else if ((new String(request_type_string)).compareTo("PAUSE") == 0)
				request_type = PAUSE;
			else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)
				request_type = TEARDOWN;

			if (request_type == SETUP) {
				// Extract VideoFileName from RequestLine
				VideoFileName = tokens.nextToken(); // Will get the second word of the first line (movie.Mjpeg)
			}

			// Read second line of the header which could be: CSeq: 1
			String SeqNumLine = RTSPBufferedReader.readLine();
			System.out.println(SeqNumLine);
			tokens = new StringTokenizer(SeqNumLine);
			tokens.nextToken(); // Get the first word (CSeq) and do nothing with it
			RTSPSeqNb = Integer.parseInt(tokens.nextToken()); // Get the second word and put it in variable (1)

			// Get the third (last) line which in the SETUP case is: Transport: RTP/UDP; client_port= 25000
			String LastLine = RTSPBufferedReader.readLine();
			System.out.println(LastLine);

			if (request_type == SETUP) {
				// Extract RTP_dest_port from the third (last) line of the headers send by the client
				tokens = new StringTokenizer(LastLine);
				for (int i = 0; i < 3; i++) {
					tokens.nextToken(); // Skip unused stuff three times (Transport:, RTP/UDP; and client_port= will be skipped)
				}
				RTP_dest_port = Integer.parseInt(tokens.nextToken()); // Get the fourth word (25000)
			}
			// else LastLine will be the SessionId line ... do not check for now.
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}
		return (request_type);
	}

	/*
	 * Will send a RTSP reponse back via the outputstream off the socket
	 * which is made special for communicating with the client.
	 */
	private void send_RTSP_response() {
		try {
			RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF); // Version of RTP and success code is given
			RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF); // Sequence number is given
			RTSPBufferedWriter.write("Session: " + RTSP_ID + CRLF); // Session id is given (123456)
			RTSPBufferedWriter.flush();
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}
	}
}
