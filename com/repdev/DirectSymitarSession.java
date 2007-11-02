/**
 *  RepDev - RepGen IDE for Symitar
 *  Copyright (C) 2007  Jake Poznanski, Ryan Schultz, Sean Delaney
 *  http://repdev.org/ <support@repdev.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.repdev;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * This is the main connection object to the Symitar host, it provides all the routines you would need to connect
 * 
 * Each action leaves no side effects for any other one in general, but the run repgen mode is a bit special, as it as an interface 
 * that it uses to get prompts which could technically use a backwards form of recursion to mess everything up.
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
	Thread keepAlive;

	private String log(String str) {
		System.out.println(str);
		return str;
	}
	
	private String log(Object o){
		return log( o.toString() );
	}

	@Override
	public SessionError connect(String server, String aixUsername, String aixPassword, int sym, String userID) {
		String line = "";

		if( connected )
			return SessionError.ALREADY_CONNECTED;
		
		this.sym = sym;
		this.server = server;
		this.aixUsername = aixUsername;
		this.aixPassword = aixPassword;
		this.userID = userID;

		try {
			socket = new Socket(server, Config.getPort());
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

			String temp = readUntil("Password:", "[c");
		
			if( temp.indexOf("[c") == -1 ){
				line = writeLog(aixPassword + "\r", "[c", ":");
	
				if (line.indexOf("invalid login") != -1){
					disconnect();
					return SessionError.AIX_LOGIN_WRONG;
				}
			}

			write("WINDOWSLEVEL=3\n");
			
			temp = readUntil( "$ ", "SymStart~Global");
			
			System.out.println(temp);
			
			
			if( temp.contains("$ ") )
				write("sym " + sym + "\r");

			Command current;

			while (!(current = readNextCommand()).getCommand().equals("Input")){
				log(current);
				
				if( current.getCommand().equals("SymLogonError") && current.getParameters().get("Text").contains("Too Many Invalid Password Attempts") ){
					disconnect();
					return SessionError.CONSOLE_BLOCKED;
				}
			}

			log(current.toString());

			write(userID + "\r");

			current = readNextCommand();
			log("USER RESPONSE: " + current.getCommand());
			if (current.getCommand().equals("SymLogonInvalidUser")){
				if(RepDevMain.DEVELOPER){
					System.out.println("Bad password");
					//TODO:fix (almost done)
					String newpass = FailedLogonShell.checkPass();
					
						write("\r");
						Command current1;
						while (!(current1 = readNextCommand()).getCommand().equals("Input")){
							log(current1);
							if( current1.getCommand().equals("SymLogonError") && current1.getParameters().get("Text").contains("Too Many Invalid Password Attempts") ){
								disconnect();
								return SessionError.CONSOLE_BLOCKED;
							}
						}
						log(current1.toString());
						write(newpass + "\r");
					if (current1.getCommand().equals("SymLogonInvalidUser")){
						System.out.println("Console Blocked");
						disconnect();
						return SessionError.CONSOLE_BLOCKED;
					}else{
						userID=newpass;
						Config.setLastUserID(newpass);
						
						SymitarSession session = RepDevMain.SYMITAR_SESSIONS.get(sym);
						
						if (session != null && newpass != null && newpass.length() >= 3){
							ProjectManager.prefix = newpass.substring(0, 3);
							session.userID = newpass;
						}else if(session != null && newpass != null){
							ProjectManager.prefix = newpass;
							session.userID = newpass;
						}
					}
						System.out.println(ProjectManager.prefix);
						
						Command current2 = readNextCommand(); 
						write("\r");
						if (current2.getCommand().equals("SymLogonInvalidUser")){
							System.out.println("Console Blocked");
							disconnect();
							return SessionError.CONSOLE_BLOCKED;
						}
						
				}else{
					disconnect();
					return SessionError.USERID_INVALID;
				}
			}
			
			write("\r");
			readNextCommand();
			
			write("\r");
			log(readNextCommand().toString());
			
			connected = true;
			log("Connected to Symitar!");
			
			//Establish keepalive timer, every 55 seconds send an empty command
			keepAlive = new Thread(new Runnable(){

				public void run() {
					try{
						while(true){
							Thread.sleep(55000);
							log("Keep Alive");
							wakeUp();
						}
					}
					catch(InterruptedException e){
						System.out.println("Terminating keepalive thread");
					}
					catch(Exception e){
						e.printStackTrace();
					}
				}
				
			});
			keepAlive.start();
			
		} catch (UnknownHostException e) {
			e.printStackTrace();
			disconnect();
			return SessionError.SERVER_NOT_FOUND;
		} catch (IOException e) {
			e.printStackTrace();
			disconnect();
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
			
			if( data.indexOf(Character.toString((char) 253)) != -1 && data.indexOf(Character.toString((char) 254)) != -1)
				return data.substring(data.indexOf(Character.toString((char) 253)) + 1, data.indexOf(Character.toString((char) 254)));
			else
				return "";
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

		Command cmd = Command.parse(data.substring(0, data.length() - 1));
		
		//Filter out Messages that come in asychronously and fuck everything up
		if( cmd.getCommand().equals("MsgDlg") && cmd.getParameters().get("Text").contains("From PID") )
			return readNextCommand();
		else
			return cmd;
	
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
		try {
			if( keepAlive != null)
				keepAlive.interrupt();
			
			if( in != null)
				in.close();
			
			if( out != null)
				out.close();
			
			if( socket != null)
				socket.close();
		} catch (Exception e) {
			return SessionError.IO_ERROR;
		}

		return SessionError.NONE;
	}

	private synchronized void wakeUp(){
		write(new Command("WakeUp"));
	}
	
	@Override
	public synchronized ErrorCheckResult errorCheckRepGen(String filename) {
		Command cur;
		String error = "", errFile = "";
		int line = -1, column = -1;

		if( !connected )
			return null;
		
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
				return new ErrorCheckResult(filename,"File does not exist on server!",-1,-1,ErrorCheckResult.Type.ERROR);
			}
			
			if( cur.getParameters().get("Action").equals("NoError") ){
				readNextCommand();
				return new ErrorCheckResult(filename,"",-1,-1,ErrorCheckResult.Type.NO_ERROR);
			}
			
			if( cur.getParameters().get("Action").equals("Init")){
				errFile = cur.getParameters().get("FileName");
				
			  	while( !(cur=readNextCommand()).getParameters().get("Action").equals("DisplayEdit")){
			  		if( cur.getParameters().get("Action").equals("FileInfo") )
			  		{
			  			line = Integer.parseInt(cur.getParameters().get("Line").replace(",", ""));
			  			column = Integer.parseInt(cur.getParameters().get("Col").replace(",", ""));
			  		}
			  		else if( cur.getParameters().get("Action").equals("ErrText") )
			  			error += cur.getParameters().get("Line") + " ";
			  		
			  		log(cur.toString());
			  	}
			  	
			  	readNextCommand();
			  	
			  	return new ErrorCheckResult(errFile,error.trim(),line,column,ErrorCheckResult.Type.ERROR);  		  	
			}
		
		}
		catch(IOException e)
		{
			return null;
		}
		
		return null;
	}

	@Override
	public synchronized boolean fileExists(SymitarFile file){
		return getFileList(file.getType(), file.getName()).size() > 0;
	}
	
	@Override
	public synchronized String getFile(SymitarFile file) {
		StringBuilder data = new StringBuilder();
		final long maxSize = 2097152; //Don't download more than 2MB, otherwise things get ugly
		boolean wroteSizeWarning = false;
		
		if( !connected )
			return null;
		
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
		else if( file.getType() == FileType.REPORT)
			retrieve.getParameters().put("Type", "Report");
		
		retrieve.getParameters().put("Name", file.getName());

		write(retrieve);
		
		try {
			while (true) {
				current = readNextCommand();
				
				if (current.getParameters().get("Status") != null && current.getParameters().get("Status").contains("No such file or directory"))
					return "";
				else if( current.getParameters().get("Status") != null )
					return null;

				if (current.getParameters().get("Done") != null){
					return data.toString();
				}

				if( data.length() < maxSize){
					data.append(current.getFileData());
					
					if( file.getType() == FileType.REPORT )
						data.append( "\n");
				}
				else if( !wroteSizeWarning ){
					data.insert(0,"WARNING - This file exceeds the 2MB limit that RepDev has for loading files. This text should only be used as a preview!\n\n");
					data.append("\n\nWARNING - This file exceeds the 2MB limit that RepDev has for loading files. This text should only be used as a preview!");
					wroteSizeWarning = true;
				}
			}
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public synchronized ArrayList<SymitarFile> getFileList(FileType type, String search) {
		ArrayList<SymitarFile> toRet = new ArrayList<SymitarFile>();
		Command current;
		
		if( !connected )
			return toRet;

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
				return toRet;
			}

			//log(current.toString());

			if (current.getParameters().get("Status") != null)
				break;

			if( current.getParameters().get("Name") != null)
				toRet.add(new SymitarFile(sym, current.getParameters().get("Name"), type, Util.parseDate(current.getParameters().get("Date"), current.getParameters().get("Time")), Integer.parseInt(current.getParameters().get("Size"))));
		
						
			if(current.getParameters().get("Done") != null)
				break;
		}

		return toRet;
	}


	@Override
	public boolean isConnected() {
		return connected;
	}

	//TODO: Add more error checking
	@Override
	public SessionError printFileLPT(SymitarFile file, int queue, boolean formsOverride, int formLength, int startPage, int endPage, int copies, boolean landscape, boolean duplex, int queuePriority) {
		Command cur;
		
		if( !connected )
			return SessionError.NOT_CONNECTED;
			
		if( !(file.getType() == FileType.REPORT))
			return SessionError.INVALID_FILE_TYPE;
		
		try {
			write("mm1" + (char)27); //Managment menu #3- repgen, of course!!
			
			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);

			write("P\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			write( file.getName() + "\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			write( "\r");
			
			//Something wierd was added recently, it waits for a specific queue prompt now.
			while( (cur = readNextCommand()) != null){
				log(cur);
				
				if( cur.getCommand().equals("Input") && cur.getParameters().get("HelpCode").equals("10008") )
					break;
			}

			
			write( queue + "\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input")){
				log(cur);
				
				if( cur.getCommand().equals("MsgDlg") && cur.getParameters().get("Type").equals("Error") ){
					 wakeUp();
					 return SessionError.INVALID_QUEUE;
				}
			}
			
			write( "\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			write( (formsOverride ? "1" : "0")+ "\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			if( formsOverride ){
				write( formLength + "\r");

				while( !(cur = readNextCommand()).getCommand().equals("Input"))
					log(cur);
			}
			
			write( startPage + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);

			write( endPage + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);

			write( copies + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);

			write( (landscape ? "1" : "0") + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			write( (duplex ? "1" : "0") + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input"))
				log(cur);
			
			write("4\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input")){
				log(cur);
				
				if( cur.getCommand().equals("MsgDlg") && cur.getParameters().get("Type").equals("Error") ){
					 wakeUp();
					 return SessionError.INPUT_ERROR;
				}
			}
			
		} catch (IOException e) {
			e.printStackTrace();
			return SessionError.IO_ERROR;
		}
		
		return SessionError.NONE;
	}

	@Override
	public SessionError printFileTPT(SymitarFile file, int queue) {
		
		return null;
	}

	@Override
	public SessionError removeFile(SymitarFile file) {
		Command delete = new Command();
		delete.setCommand("File");
		delete.getParameters().put("Action", "Delete");

		if (file.getType() == FileType.REPGEN)
			delete.getParameters().put("Type", "RepWriter");
		else if (file.getType() == FileType.HELP)
			delete.getParameters().put("Type", "Help");
		else if (file.getType() == FileType.LETTER)
			delete.getParameters().put("Type", "Letter");
		else if (file.getType() == FileType.REPORT)
			delete.getParameters().put("Type", "Report");

		delete.getParameters().put("Name", file.getName());

		write(delete);

		Command current = null;

		try {
			current = readNextCommand();

			if (current.getParameters().get("Status") != null && current.getParameters().get("Status").contains("No such file or directory"))
				return SessionError.ARGUMENT_ERROR;
			else if (current.getParameters().get("Status") != null)
				return SessionError.FILENAME_TOO_LONG;
			else if (current.getParameters().get("Done") != null)
				return SessionError.NONE;

		} catch (IOException e) {
			e.printStackTrace();
		}

		return SessionError.IO_ERROR;
	}

	@Override
	public synchronized RunFMResult runBatchFM(String searchTitle, int searchDays, FMFile file, int queue) {
		RunFMResult result = new RunFMResult();
		int[] queueCounts = new int[9999];
		boolean[] queueAvailable = new boolean[9999];
		
		for( int i = 0; i < queueCounts.length; i++)
			queueCounts[i] = -1;
		
		Command cur;
		try {
			write("mm0" + (char)27);
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("1\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("24\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			// menu option for batch fm
			write("5\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write(file.ordinal() + "\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("0\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write(searchTitle + "\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write( searchDays + "\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			if( file == FMFile.ACCOUNT ){
				write("1\r");
				while( !(cur = readNextCommand()).getCommand().equals("Input") )
					log(cur);
			}
			
			write(result.getResultTitle() + "\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("1\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("0\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") ){
				log(cur);
				if( cur.getParameters().get("Action").equals("DisplayLine") && cur.getParameters().get("Text").contains("Batch Queues Available:")){
					String line = cur.getParameters().get("Text");				
					String[] tempQueues = line.substring(line.indexOf(":") + 1).split(",");
					
					int i = 0;
					
					for( String temp : tempQueues){
						temp = temp.trim();
						
						if( temp.contains("-"))
						{
							String[] tempList = temp.split("-");
							
							int start = Integer.parseInt(tempList[0].trim());
							int end = Integer.parseInt(tempList[1].trim());
							
							for( int x = start; x <= end; x++){
								queueAvailable[x]=true;
							}
						}
						else
						{
							queueAvailable[Integer.parseInt(temp)] = true;
						}
						
						i++;
					}
				}
			}
//			Batch queue selection
			Command getQueues = new Command("Misc");
			getQueues.getParameters().put("InfoType", "BatchQueues");
			write(getQueues);
	
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				if( cur.getParameters().get("Action").equals("QueueEntry") && cur.getParameters().get("Stat").equals("Running"))
					if(queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] < 0)
						queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] = 1;
					else
						queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))]++;
				else if( cur.getParameters().get("Action").equals("QueueEmpty"))
					queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] = 0;
			}
			int lastGood = -1;
			
			if( (queue != -1 && !queueAvailable[queue]) || queue == -1 )
			{
				for( queue = 0; queue < queueCounts.length; queue++)
				{
					if( queueAvailable[queue])
						lastGood = queue;

					if( queueAvailable[queue] && queueCounts[queue] == 0)
						break;
				}
				
				queue = lastGood;
			}
			
			write( queue + "\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write("1\r");
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write(getQueues);
			
			int newestTime = 0;
			
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				//Get the Sequence for the latest running one at this point, and return it so we can keep track of it
				if( cur.getParameters().get("Action").equals("QueueEntry") ){
					int curTime = 0;
					String timeStr = cur.getParameters().get("Time");
					curTime = Integer.parseInt(timeStr.substring(timeStr.lastIndexOf(":")+1));
					curTime += 60 * Integer.parseInt(timeStr.substring(timeStr.indexOf(":")+1, timeStr.lastIndexOf(":")));
					curTime += 3600 * Integer.parseInt(timeStr.substring(0,timeStr.indexOf(":")));
					
					if( curTime > newestTime )
					{
						newestTime = curTime;
						result.setSeq(Integer.parseInt(cur.getParameters().get("Seq")));
						
					}
				}
			}
			
		} catch (IOException e) {
			System.err.println("ERROR: " + e.getMessage());
		}
		
		
		return result;
	}
	
	/**
	 * Helper method for runRepGen stuff
	 * @param progress
	 * @param value
	 * @param text
	 * @param str
	 */
	private void setProgress(ProgressBar progress, int value, Text text, String str){
		if( progress != null && !progress.isDisposed() )
			progress.setSelection(value);
		
		if( text != null && str != null && !text.isDisposed())
			text.setText(str.replace("\r", "\n"));
	}
	
	/**
	 * Queue, -1 for first available,
	 * any other number for a specific one
	 * 
	 * Progressbar is useful for updating GUI stuff
	 * 
	 * The listener object is the best way to ask for prompts while still blocking from other threads.
	 * 
	 * 
	 * This allows for amodal dialogs for running, and also the option to cancel
	 * Non polling would be nice, but maybe tricky to implement, I will look into it.
	 */
	public synchronized RunRepgenResult runRepGen(String name, int queue, ProgressBar progress, Text text, PromptListener prompter) {
		Command cur, old;
		boolean isError = false;
		int[] queueCounts = new int[9999];
		boolean[] queueAvailable = new boolean[9999];
		
		//We cannot use queueCounts as an availbility thing, though it would be nice
		//The two arrays are parsed seperately, queueCounts from the list of queus and wahts in them
		//queueAvailable is from a seperate request saying which ones can actually run repwriters
		
		int seq = -1, time = 0;
		
		for( int i = 0; i < queueCounts.length; i++)
			queueCounts[i] = -1;
		
		setProgress(progress,0, text, "Queuing batch run, please wait...");
		
		try{
			write("mm0" + (char)27);
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			setProgress(progress,5, null, null);
			
			write("1\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			setProgress(progress,10, null, null);
			
			write("11\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			setProgress(progress,15, text, "Please answer prompts");
			
			write( name + "\r");
			
			isError = false;
			while( (cur = readNextCommand()) != null ){
							
				log(cur);
				
				if( cur.getCommand().equals("Input") && cur.getParameters().get("HelpCode").equals("20301"))
					break;
				else if( cur.getCommand().equals("Input") ){
					String result = prompter.getPrompt(cur.getParameters().get("Prompt"));
					
					if( result == null ){
						write( Character.toString((char)0x1b));
						
						while( !(cur = readNextCommand()).getCommand().equals("Input") )
							log(cur);
						
						return new RunRepgenResult(-1,0);
					}
					else
						write( result.trim() + "\r");
				}
				else if( cur.getCommand().equals("Bell") ){
					setProgress(progress, 15, text, "That prompt input is invalid, please reenter");
				}
				else if( cur.getCommand().equals("Batch") && cur.getParameters().get("Text").contains("No such file or directory")){
					old = cur;
					
					while( !(cur = readNextCommand()).getCommand().equals("Input") )
						log(cur);
					
					setProgress(progress,100, text, "Error: No such file or directory");					
					
					return new RunRepgenResult(-1,0);
				}
				else if( cur.getCommand().equals("SpecfileErr"))
					isError = true;
				else if (isError && cur.getCommand().equals("Batch") && cur.getParameters().get("Action").equals("DisplayLine")){
					old = cur;
					
					while( !(cur = readNextCommand()).getCommand().equals("Input") )
						log(cur);
					
					setProgress(progress,100, text, "There was an error in your program,\n that is preventing it from running:\n\n" + old.getParameters().get("Text"));	
					
					return new RunRepgenResult(-1,0);
				}
				else if( cur.getCommand().equals("Batch") && cur.getParameters().get("Action").equals("DisplayLine")){
					setProgress(null, 0, text, text.getText() + "\n" + cur.getParameters().get("Text"));
				}
			}
			
			write( "\r" );
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			setProgress(progress,20, null, null);	
			
			write( "0\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") ){
				log(cur);
				if( cur.getParameters().get("Action").equals("DisplayLine") && cur.getParameters().get("Text").contains("Batch Queues Available:")){
					String line = cur.getParameters().get("Text");				
					String[] tempQueues = line.substring(line.indexOf(":") + 1).split(",");
					
					int i = 0;
					
					for( String temp : tempQueues){
						temp = temp.trim();
						
						if( temp.contains("-"))
						{
							String[] tempList = temp.split("-");
							
							int start = Integer.parseInt(tempList[0].trim());
							int end = Integer.parseInt(tempList[1].trim());
							
							for( int x = start; x <= end; x++){
								queueAvailable[x]=true;
							}
						}
						else
						{
							queueAvailable[Integer.parseInt(temp)] = true;
						}
						
						i++;
					}
				}
			}
			
			setProgress(progress,25, null, null);	
				
			//Batch queue selection
			Command getQueues = new Command("Misc");
			getQueues.getParameters().put("InfoType", "BatchQueues");
			write(getQueues);
	
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				if( cur.getParameters().get("Action").equals("QueueEntry") && cur.getParameters().get("Stat").equals("Running"))
					if(queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] < 0)
						queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] = 1;
					else
						queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))]++;
				else if( cur.getParameters().get("Action").equals("QueueEmpty"))
					queueCounts[Integer.parseInt(cur.getParameters().get("Queue"))] = 0;
			}
			int lastGood = -1;
			
			if( (queue != -1 && !queueAvailable[queue]) || queue == -1 )
			{
				for( queue = 0; queue < queueCounts.length; queue++)
				{
					if( queueAvailable[queue])
						lastGood = queue;

					if( queueAvailable[queue] && queueCounts[queue] == 0)
						break;
				}
				
				queue = lastGood;
			}
			
			write( queue + "\r");

			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			setProgress(progress,30, null, null);	
			
			write( "1\r");
			
			while( !(cur = readNextCommand()).getCommand().equals("Input") )
				log(cur);
			
			write(getQueues);
			
			int newestTime = 0;
			
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				//Get the Sequence for the latest running one at this point, and return it so we can keep track of it
				if( cur.getParameters().get("Action").equals("QueueEntry") ){
					int curTime = 0;
					String timeStr = cur.getParameters().get("Time");
					curTime = Integer.parseInt(timeStr.substring(timeStr.lastIndexOf(":")+1));
					curTime += 60 * Integer.parseInt(timeStr.substring(timeStr.indexOf(":")+1, timeStr.lastIndexOf(":")));
					curTime += 3600 * Integer.parseInt(timeStr.substring(0,timeStr.indexOf(":")));
					
					if( curTime > newestTime )
					{
						newestTime = curTime;
						seq = Integer.parseInt(cur.getParameters().get("Seq"));
						time = curTime;
					}
				}
			}
		}
		catch(Exception e){
			e.printStackTrace();
			
			return new RunRepgenResult(-1,0);
		}
		
		setProgress(progress,50, text, "Repgen queued\nWaiting for batch job to finish");	
		
		return new RunRepgenResult(seq,time);
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
		
		if( file == null || text == null)
			return SessionError.ARGUMENT_ERROR;

		log("Saving file: " + file);
		
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

		wakeUp();
		write(store);

		try {
			current = readNextCommand();
		
			if( current == null ){
				System.out.println("Returned null for the save file command, ack!! trying to restore");
				
				wakeUp();
				write(store);
				
				current = readNextCommand();
			}
			else{
				System.out.println("Save file command:\n" + current.toString()+ "\n");
			}
			
			if( current.getParameters().get("Status") != null && current.getParameters().get("Status").contains("Filename is too long") )
				return SessionError.FILENAME_TOO_LONG;

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
	public synchronized ErrorCheckResult installRepgen(String filename) {
		Command cur;
		String error = "", errFile = "";
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
				return new ErrorCheckResult(filename,"File does not exist on server!",-1,-1,ErrorCheckResult.Type.ERROR);
			}
			
			if( cur.getCommand().equals("SpecfileData") ){
				readNextCommand();
				write("1\r");
				
				readNextCommand();
				readNextCommand();
				return new ErrorCheckResult(filename,"",Integer.parseInt(cur.getParameters().get("Size").replace(",", "")),ErrorCheckResult.Type.INSTALLED_SUCCESSFULLY);
				
			}
			
			if( cur.getParameters().get("Action").equals("Init")){
				errFile = cur.getParameters().get("FileName");
				
			  	while( !(cur=readNextCommand()).getParameters().get("Action").equals("DisplayEdit")){
			  		if( cur.getParameters().get("Action").equals("FileInfo") )
			  		{
			  			line = Integer.parseInt(cur.getParameters().get("Line").replace(",", ""));
			  			column = Integer.parseInt(cur.getParameters().get("Col").replace(",", ""));
			  		}
			  		else if( cur.getParameters().get("Action").equals("ErrText") )
			  			error += cur.getParameters().get("Line") + " ";
			  		
			  		log(cur.toString());
			  	}
			  	
			  	readNextCommand();
			  	
			  	return new ErrorCheckResult(errFile,error.trim(),line,column,ErrorCheckResult.Type.ERROR);  		  	
			}
		
		}
		catch(IOException e)
		{
			return null;
		}
		
		return null;
	}

	@Override
	/**
	 * Remeber that batch queue sequence numbers are not related to print queue ones!
	 */
	public synchronized boolean isSeqRunning(int seq) {
		Command cur;
		boolean running = false;
		
		if( !connected )
			return false;
		
		//Batch queue selection
		Command getQueues = new Command("Misc");
		getQueues.getParameters().put("InfoType", "BatchQueues");
		write(getQueues);
		
		try{
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				if( cur.getParameters().get("Action").equals("QueueEntry") && Integer.parseInt(cur.getParameters().get("Seq"))==seq)
					running = true;
			}
		}
		catch(IOException e){
			return false;
		}
		
		return running;
	}
	
	

	@Override
	public void terminateRepgen(int seq) {
	
	}

	@Override
	public synchronized ArrayList<PrintItem> getPrintItems(String query, int limit) {
		ArrayList<PrintItem> items = new ArrayList<PrintItem>();
		Command cur;
		
		if( !connected )
			return null;
		
		limit = Math.min(40, limit);
		
		Command getItems = new Command("File");
		getItems.getParameters().put("Action", "List");
		getItems.getParameters().put("MaxCount", "50");
		getItems.getParameters().put("Query", "LAST " + limit + " \"+" + query + "+\"");// + " \"+" + query + "+\"");
		getItems.getParameters().put("Type", "Report");
		
		write(getItems);
		
		try {
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				if( cur.getParameters().get("Sequence") != null ){
					try {
						Date date = Util.parseDate(cur.getParameters().get("Date"), cur.getParameters().get("Time"));
						
						items.add( new PrintItem(cur.getParameters().get("Title"),Integer.parseInt(cur.getParameters().get("Sequence")),Integer.parseInt(cur.getParameters().get("Size")),Integer.parseInt(cur.getParameters().get("PageCount")),Integer.parseInt(cur.getParameters().get("BatchSeq")),date ));
					} catch (NumberFormatException e) {
						e.printStackTrace();
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		Collections.sort(items);
		
		return items;
	}

	@Override
	public synchronized ArrayList<PrintItem> getPrintItems(Sequence seq) {
		ArrayList<PrintItem> items = new ArrayList<PrintItem>();
		
		Command cur;
		
		Calendar seqCal = new GregorianCalendar();
		seqCal.setTime(seq.getDate());
		
		if( !connected )
			return null;
		
		Command getItems = new Command("File");
		getItems.getParameters().put("Action", "List");
		getItems.getParameters().put("MaxCount", "150");
		getItems.getParameters().put("Query", "BATCH " + seq.getSeq());
		getItems.getParameters().put("Type", "Report");
		
		write(getItems);
		log("Requesting batch sequence: " + seq);
		
		try {
			while( (cur = readNextCommand()).getParameters().get("Done") == null ){
				log(cur);
				
				if( cur.getParameters().get("Sequence") != null ){
					try {
					   Date date = Util.parseDate(cur.getParameters().get("Date"), cur.getParameters().get("Time"));
					   Calendar curCal = new GregorianCalendar();
					   curCal.setTime(date);
					   
					   if( curCal.get(Calendar.DAY_OF_YEAR) == seqCal.get(Calendar.DAY_OF_YEAR))
						   items.add( new PrintItem(cur.getParameters().get("Title"),Integer.parseInt(cur.getParameters().get("Sequence")),Integer.parseInt(cur.getParameters().get("Size")),Integer.parseInt(cur.getParameters().get("PageCount")),Integer.parseInt(cur.getParameters().get("BatchSeq")),date ));
					} catch (NumberFormatException e) {
						e.printStackTrace();
					}
				}
					
			}
					
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		Collections.sort(items);

		
		return items;
	}

	@Override
	public synchronized SessionError renameFile(SymitarFile file, String newName) {
		Command retrieve = new Command();
		retrieve.setCommand("File");
		retrieve.getParameters().put("Action", "Rename");

		if (file.getType() == FileType.REPGEN)
			retrieve.getParameters().put("Type", "RepWriter");
		else if (file.getType() == FileType.HELP)
			retrieve.getParameters().put("Type", "Help");
		else if (file.getType() == FileType.LETTER)
			retrieve.getParameters().put("Type", "Letter");
		else if (file.getType() == FileType.REPORT)
			retrieve.getParameters().put("Type", "Report");

		retrieve.getParameters().put("Name", file.getName());
		retrieve.getParameters().put("NewName", newName);

		write(retrieve);

		Command current = null;

		try {
			current = readNextCommand();

			if (current.getParameters().get("Status") != null && current.getParameters().get("Status").contains("No such file or directory"))
				return SessionError.ARGUMENT_ERROR;
			else if (current.getParameters().get("Status") != null)
				return SessionError.FILENAME_TOO_LONG;
			else if (current.getParameters().get("Done") != null)
				return SessionError.NONE;

		} catch (IOException e) {
			e.printStackTrace();
		}

		return SessionError.IO_ERROR;
	}

}
