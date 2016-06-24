package server;

import java.io.FileInputStream;

public class VideoStream {
	FileInputStream fis; // video file
	int frame_nb; // current frame number

	/*
	 * Constructor expecting the filename of the video
	 * which is passed in by the client to the server
	 * and by the server to this constructor.
	 */
	public VideoStream(String filename) throws Exception {

		// Initialize variables
		fis = new FileInputStream("C:/Users/Jeffrey/Documents/" + filename); // Opening a connection to the file
		frame_nb = 0;
	}

	/*
	 * Returns the next frame as an array of byte and the size of the frame
	 */
	public int getnextframe(byte[] frame) throws Exception {
		int length = 0;
		String length_string;
		byte[] frame_length = new byte[5];

		// Read current frame length
		// Reads the first five bytes and puts it in
		// the frame_length array.
		fis.read(frame_length, 0, 5);

		// Transform frame_length to integer
		length_string = new String(frame_length);
		length = Integer.parseInt(length_string);

		return (fis.read(frame, 0, length));
	}
}