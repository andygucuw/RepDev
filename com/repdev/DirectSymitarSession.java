package com.repdev;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;

/**
 * This is the main connection object to the Symitar host, it provides all the routines you would need to connect
 * 
 * Each action leaves no side effects for any other one in general, but the run repgen mode is a bit special
 * 
 * Provides classes and methods also to help in reading commands from the server, parsing them, creating new ones, etc.
 * 
 * @author Jake Poznanski
 *
 */
public class DirectSymitarSession extends SymitarSession {
	Socket socket;
	BufferedReader in;
	PrintWriter out;
	boolean connected = false;

	@Override
	public int acceptRepGenQuery(String value) {
		// TODO Auto-generated method stub
		return 0;
	}

	private String log(String str) {
		System.out.println(str);
		return str;
	}

	@Override
	public SessionError connect(String server, String aixUsername, String aixPassword, int sym, String userID) {
		String line = "";

		this.server = server;
		this.aixUsername = aixUsername;
		this.aixPassword = aixPassword;
		this.userID = userID;

		try {
			socket = new Socket(server, 23);
			socket.setKeepAlive(true);

			// Constant commands, these are the basic telnet establishment
			// stuffs, which really don't change, so I just send them directly
			char init1[] = { 0xff, 0xfb, 0x18 };
			char init2[] = { 0xff, 0xfa, 0x18, 0x00, 0x61, 0x69, 0x78, 0x74, 0x65, 0x72, 0x6d, 0xff, 0xf0 };
			char init3[] = { 0xff, 0xfd, 0x01 };
			char init4[] = { 0xff, 0xfd, 0x03, 0xff, 0xfc, 0x1f, 0xff, 0xfc, 0x01 };

			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			out = new PrintWriter(socket.getOutputStream());

			out.print(init1);
			out.print(init2);
			out.print(init3);
			out.print(init4);
			out.print(aixUsername + "\r");
			out.flush();

			log(readUntil("Password:"));

			line = writeLog(aixPassword + "\r", "[c", ":");

			if (line.indexOf("invalid login") != -1)
				return SessionError.AIX_LOGIN_WRONG;

			writeLog("WINDOWSLEVEL=3\n", "$ ");
			write("sym " + sym + "\r");

			Command current;

			while (!(current = readNextCommand()).getCommand().equals("Input"))
				;

			log(current.toString());

			write(userID + "\r");

			current = readNextCommand();
			log(current.getCommand());

			if (current.getCommand().equals("SymLogonInvalidUser"))
				return SessionError.USERID_INVALID;
			
			write("\r");
			log(readNextCommand().toString());
			
			write("\r");
			log(readNextCommand().toString());
			
			connected = true;
			log("Connected to Symitar!");
		} catch (UnknownHostException e) {
			e.printStackTrace();
			return SessionError.SERVER_NOT_FOUND;
		} catch (IOException e) {
			e.printStackTrace();
			return SessionError.IO_ERROR;
		}

		return SessionError.NONE;
	}

	private static class Command {
		String command = "";
		HashMap<String, String> parameters = new HashMap<String, String>();
		String data = "";
		static Pattern commandPattern = Pattern.compile("(.*?)~.*");
		static int currentMessageId = 10000;

		public Command(String command) {
			parameters.put("MsgId", String.valueOf(currentMessageId));
			currentMessageId++;

			this.command = command;
		}

		public Command(String command, HashMap<String, String> parameters, String data) {
			super();
			this.command = command;
			this.parameters = parameters;
			this.data = data;

			parameters.put("MsgId", String.valueOf(currentMessageId));
			currentMessageId++;
		}

		public Command() {
			parameters.put("MsgId", String.valueOf(currentMessageId));
			currentMessageId++;
		}

		// Returns string containing any file data sent in this message
		public String getFileData() {
			return data.substring(data.indexOf(Character.toString((char) 253)) + 1, data.indexOf(Character.toString((char) 254)));
		}

		public String sendStr() {
			String data = "";
			data += command + "~";

			for (String key : parameters.keySet())
				if (parameters.get(key).equals(""))
					data += key + "~";
				else
					data += key + "=" + parameters.get(key) + "~";

			data = data.substring(0, data.length() - 1);

			return Character.toString((char) 0x07) + data.length() + "\r" + data;
		}

		public static Command parse(String data) {
			String[] sep;

			Command command = new Command();
			command.setData(data);

			if (data.indexOf("~") != -1 && data.indexOf(253) == -1) {
				Matcher match;
				match = commandPattern.matcher(data);
				match.matches();

				command.setCommand(match.group(1));
				sep = data.substring(match.group(1).length() + 1).split("~");

				for (String cur : sep) {
					if (cur.indexOf("=") == -1)
						command.getParameters().put(cur, "");
					else
						command.getParameters().put(cur.substring(0, cur.indexOf("=")), cur.substring(cur.indexOf("=") + 1));
				}
			} else
				command.setCommand(data);

			return command;
		}

		public String toString() {
			return data;
		}

		public String getCommand() {
			return command;
		}

		public void setCommand(String command) {
			this.command = command;
		}

		public String getData() {
			return data;
		}

		public void setData(String data) {
			this.data = data;
		}

		public HashMap<String, String> getParameters() {
			return parameters;
		}

		public void setParameters(HashMap<String, String> parameters) {
			this.parameters = parameters;
		}
	}

	private void write(String str) {
		out.write(str);
		out.flush();
	}

	private void write(Command cmd) {
		write(cmd.sendStr());
	}

	private String writeLog(String command, String... waitFor) throws IOException {
		write(command);
		return log(readUntil(waitFor));
	}

	private Command readNextCommand() throws IOException {
		readUntil(Character.toString((char) 0x1b) + Character.toString((char) 0xfe));
		String data = readUntil(Character.toString((char) 0xfc));

		return Command.parse(data.substring(0, data.length() - 1));
	}

	private String readUntil(String... strs) throws IOException {
		String buf = "";

		while (true) {
			int cur = in.read();
			buf += (char) cur;
			for (String str : strs)
				if (buf.indexOf(str) != -1)
					return buf;
		}
	}

	@Override
	public SessionError disconnect() {
		if (!connected)
			return SessionError.NOT_CONNECTED;

		try {
			in.close();
			out.close();
			socket.close();
		} catch (Exception e) {
			return SessionError.IO_ERROR;
		}

		return null;
	}

	@Override
	public synchronized ErrorCheckResult errorCheckRepGen(String filename) {
		Command cur;
		String error = "";
		int line = -1, column = -1;
		
		try{

			write("mm3" + (char)27); //Managment menu #3- repgen, of course!!
			
			log(readNextCommand().toString());

			write("7\r");
			log(readNextCommand().toString());
			log(readNextCommand().toString());
			
			write(filename+"\r");
			
			cur = readNextCommand();
			log(cur.toString());
			
			if( cur.getParameters().get("Warning") != null || cur.getParameters().get("Error") != null){
				readNextCommand();
				return new ErrorCheckResult("File does not exist on server!",-1,-1,ErrorCheckResult.Type.ERROR);
			}
			
			if( cur.getParameters().get("Action").equals("NoError") ){
				readNextCommand();
				return new ErrorCheckResult("",-1,-1,ErrorCheckResult.Type.NO_ERROR);
			}
			
			if( cur.getParameters().get("Action").equals("Init")){
			  	while( !(cur=readNextCommand()).getParameters().get("Action").equals("DisplayEdit")){
			  		if( cur.getParameters().get("Action").equals("FileInfo") )
			  		{
			  			line = Integer.parseInt(cur.getParameters().get("Line").replace(",", ""));
			  			column = Integer.parseInt(cur.getParameters().get("Col").replace(",", ""));
			  		}
			  		else if( cur.getParameters().get("Action").equals("ErrText") )
			  			error += cur.getParameters().get("Line") + "\n";
			  		
			  		log(cur.toString());
			  	}
			  	
			  	readNextCommand();
			  	
			  	return new ErrorCheckResult(error.trim(),line,column,ErrorCheckResult.Type.ERROR);  		  	
			}
		
		}
		catch(IOException e)
		{
			return null;
		}
		
		return null;
	}

	@Override
	public int getBatchOutputSequenceNumber() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public synchronized String getFile(SymitarFile file) {
		StringBuilder data = new StringBuilder();

		Command current;
		Command retrieve = new Command();
		retrieve.setCommand("File");
		retrieve.getParameters().put("Action", "Retrieve");

		if (file.getType() == FileType.REPGEN)
			retrieve.getParameters().put("Type", "RepWriter");
		else if (file.getType() == FileType.HELP)
			retrieve.getParameters().put("Type", "Help");
		else if (file.getType() == FileType.LETTER)
			retrieve.getParameters().put("Type", "Letter");

		retrieve.getParameters().put("Name", file.getName());

		write(retrieve);

		try {
			while (true) {
				current = readNextCommand();

				if (current.getParameters().get("Status") != null)
					return null;

				if (current.getParameters().get("Done") != null)
					return data.toString();

				data.append(current.getFileData());
			}
		} catch (IOException e) {
			return null;
		}

	}

	@Override
	public synchronized ArrayList<SymitarFile> getFileList(FileType type, String search) {
		ArrayList<SymitarFile> toRet = new ArrayList<SymitarFile>();
		Command current;

		Command list = new Command("File");

		if (type == FileType.REPGEN)
			list.getParameters().put("Type", "RepWriter");
		else if (type == FileType.HELP)
			list.getParameters().put("Type", "Help");
		else if (type == FileType.LETTER)
			list.getParameters().put("Type", "Letter");

		list.getParameters().put("Name", search);
		list.getParameters().put("Action", "List");

		write(list);

		while (true) {
			try {
				current = readNextCommand();
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}

			log(current.toString());

			if (current.getParameters().get("Status") != null)
				break;

			if( current.getParameters().get("Name") != null)
				toRet.add(new SymitarFile(current.getParameters().get("Name"), type));
			
			if(current.getParameters().get("Done") != null)
				break;
		}

		return toRet;
	}

	@Override
	public String getRepGenQuery() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getRepgenQueue() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ArrayList<Integer> getSequenceNumbers() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	@Override
	public SessionError printFileLPT(SymitarFile file, boolean formsOverride, int formLength, int startPage, int endPage, int copies, boolean landscape, boolean duplex, int queuePriority) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SessionError printFileTPT(SymitarFile file, int queue) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SessionError removeFile(SymitarFile file) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public SessionError runBatchFM(String title) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void runRepGen(String name) {
		// TODO Auto-generated method stub

	}

	@Override
	public void runRepGen(String name, int queue) {
		// TODO Auto-generated method stub

	}

	@Override
	public synchronized SessionError saveFile(SymitarFile file, String text) {
		int partSize = 3996;
		int curPart = 0;
		DecimalFormat f3 = new DecimalFormat("000");
		DecimalFormat f5 = new DecimalFormat("00000");
		char[] buf = new char[16];
		String pad20 = "";

		if (!connected)
			return SessionError.NOT_CONNECTED;

		for (int i = 0; i < 6; i++)
			pad20 += Character.toString((char) 0x20);

		Command store = new Command();
		store.setCommand("File");
		store.getParameters().put("Action", "Store");

		Command unpause = new Command();
		unpause.setCommand("WakeUp");

		Command current;

		if (file.getType() == FileType.REPGEN)
			store.getParameters().put("Type", "RepWriter");
		else if (file.getType() == FileType.HELP)
			store.getParameters().put("Type", "Help");
		else if (file.getType() == FileType.LETTER)
			store.getParameters().put("Type", "Letter");

		store.getParameters().put("Name", file.getName());

		write(store);

		try {
			current = readNextCommand();

			String[] badList = current.getParameters().get("BadCharList").split(",");

			for (String cur : badList) {
				text = text.replaceAll(Character.toString((char) Integer.parseInt(cur)), "");
			}

			String toSend = "";
			do {
				do {
					toSend = text.substring(0, Math.min(text.length(), partSize));
					write("PROT" + f3.format(curPart) + "DATA" + f5.format(toSend.length()));
					write(toSend);
					in.read(buf, 0, 16);
				} while (buf[7] == 'N'); // Resend if we get a NAK message

				curPart++;
				text = text.substring(toSend.length());
			} while (text.length() > 0);

			write("PROT" + f3.format(curPart) + "EOF" + pad20);
			in.read(buf, 0, 16);

			current = readNextCommand();
			write(unpause);
		} catch (IOException e) {
			return SessionError.IO_ERROR;
		}
		return SessionError.NONE;
	}

	@Override
	public void waitOnChange() {
		// TODO Auto-generated method stub

	}

	@Override
	public synchronized ErrorCheckResult installRepgen(String filename) {
		Command cur;
		String error = "";
		int line = -1, column = -1;
		
		try{

			write("mm3" + (char)27); //Managment menu #3- repgen, of course!!
			
			log(readNextCommand().toString());

			write("8\r");
			log(readNextCommand().toString());
			log(readNextCommand().toString());
			
			write(filename+"\r");
			
			cur = readNextCommand();
			log(cur.toString());
			
			if( cur.getParameters().get("Warning") != null || cur.getParameters().get("Error") != null){
				readNextCommand();
				return new ErrorCheckResult("File does not exist on server!",-1,-1,ErrorCheckResult.Type.ERROR);
			}
			
			if( cur.getCommand().equals("SpecfileData") ){
				readNextCommand();
				write("1\r");
				
				readNextCommand();
				readNextCommand();
				return new ErrorCheckResult("",Integer.parseInt(cur.getParameters().get("Size").replace(",", "")),ErrorCheckResult.Type.INSTALLED_SUCCESSFULLY);
				
			}
			
			if( cur.getParameters().get("Action").equals("Init")){
			  	while( !(cur=readNextCommand()).getParameters().get("Action").equals("DisplayEdit")){
			  		if( cur.getParameters().get("Action").equals("FileInfo") )
			  		{
			  			line = Integer.parseInt(cur.getParameters().get("Line").replace(",", ""));
			  			column = Integer.parseInt(cur.getParameters().get("Col").replace(",", ""));
			  		}
			  		else if( cur.getParameters().get("Action").equals("ErrText") )
			  			error += cur.getParameters().get("Line") + "\n";
			  		
			  		log(cur.toString());
			  	}
			  	
			  	readNextCommand();
			  	
			  	return new ErrorCheckResult(error.trim(),line,column,ErrorCheckResult.Type.ERROR);  		  	
			}
		
		}
		catch(IOException e)
		{
			return null;
		}
		
		return null;
	}

}
