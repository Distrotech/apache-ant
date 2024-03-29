/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.tools.ant.taskdefs.optional.ssh;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.resources.FileResource;
import org.apache.tools.ant.util.FileUtils;
import org.apache.tools.ant.util.KeepAliveOutputStream;
import org.apache.tools.ant.util.TeeOutputStream;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * Executes a command on a remote machine via ssh.
 * @since     Ant 1.6 (created February 2, 2003)
 */
public class SSHExec extends SSHBase {

    private static final int BUFFER_SIZE = 8192;
    private static final int RETRY_INTERVAL = 500;

    /** the command to execute via ssh */
    private String command = null;

    /** units are milliseconds, default is 0=infinite */
    private long maxwait = 0;

    /** for waiting for the command to finish */
    private Thread thread = null;

    private String outputProperty = null;   // like <exec>
    private File outputFile = null;   // like <exec>
    private String inputProperty = null;
    private String inputString = null;   // like <exec>
    private File inputFile = null;   // like <exec>
    private boolean append = false;   // like <exec>
    private boolean usePty = false;

    private Resource commandResource = null;

    private static final String TIMEOUT_MESSAGE =
        "Timeout period exceeded, connection dropped.";

    /**
     * Constructor for SSHExecTask.
     */
    public SSHExec() {
        super();
    }

    /**
     * Sets the command to execute on the remote host.
     *
     * @param command  The new command value
     */
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * Sets a commandResource from a file
     * @param f the value to use.
     * @since Ant 1.7.1
     */
    public void setCommandResource(String f) {
        this.commandResource = new FileResource(new File(f));
    }

    /**
     * The connection can be dropped after a specified number of
     * milliseconds. This is sometimes useful when a connection may be
     * flaky. Default is 0, which means &quot;wait forever&quot;.
     *
     * @param timeout  The new timeout value in seconds
     */
    public void setTimeout(long timeout) {
        maxwait = timeout;
    }

    /**
     * If used, stores the output of the command to the given file.
     *
     * @param output  The file to write to.
     */
    public void setOutput(File output) {
        outputFile = output;
    }

    /**
     * If used, the content of the file is piped to the remote command
     *
     * @param input  The file which provides the input data for the remote command
     *
     * @since Ant 1.8.0
     */
    public void setInput(File input) {
        inputFile = input;
    }

    /**
     * If used, the content of the property is piped to the remote command
     *
     * @param inputProperty The property which contains the input data
     * for the remote command.
     *
     * @since Ant 1.8.0
     */
    public void setInputProperty(String inputProperty) {
    	this.inputProperty = inputProperty;
    }

    /**
     * If used, the string is piped to the remote command.
     *
     * @param inputString the input data for the remote command.
     *
     * @since Ant 1.8.3
     */
    public void setInputString(String inputString) {
    	this.inputString = inputString;
    }

    /**
     * Determines if the output is appended to the file given in
     * <code>setOutput</code>. Default is false, that is, overwrite
     * the file.
     *
     * @param append  True to append to an existing file, false to overwrite.
     */
    public void setAppend(boolean append) {
        this.append = append;
    }

    /**
     * If set, the output of the command will be stored in the given property.
     *
     * @param property  The name of the property in which the command output
     *      will be stored.
     */
    public void setOutputproperty(String property) {
        outputProperty = property;
    }

    /**
     * Whether a pseudo-tty should be allocated.
     * @since Apache Ant 1.8.3
     */
    public void setUsePty(boolean b) {
        usePty = b;
    }

    /**
     * Execute the command on the remote host.
     *
     * @exception BuildException  Most likely a network error or bad parameter.
     */
    public void execute() throws BuildException {
        if (getHost() == null) {
            throw new BuildException("Host is required.");
        }
        if (getUserInfo().getName() == null) {
            throw new BuildException("Username is required.");
        }
        if (getUserInfo().getKeyfile() == null
            && getUserInfo().getPassword() == null) {
            throw new BuildException("Password or Keyfile is required.");
        }
        if (command == null && commandResource == null) {
            throw new BuildException("Command or commandResource is required.");
        }

        int numberOfInputs = (inputFile != null ? 1 : 0)
            + (inputProperty != null ? 1 : 0)
            + (inputString != null ? 1 : 0);
        if (numberOfInputs > 1) {
            throw new BuildException("You can't specify more than one of"
                                     + " inputFile, inputProperty and"
                                     + " inputString.");
        }
        if (inputFile != null && !inputFile.exists()) {
            throw new BuildException("The input file "
                                     + inputFile.getAbsolutePath()
                                     + " does not exist.");
        }

        Session session = null;
        StringBuffer output = new StringBuffer();
        try {
            session = openSession();
            /* called once */
            if (command != null) {
                log("cmd : " + command, Project.MSG_INFO);
                executeCommand(session, command, output);
            } else { // read command resource and execute for each command
                try {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(commandResource.getInputStream()));
                    String cmd;
                    while ((cmd = br.readLine()) != null) {
                        log("cmd : " + cmd, Project.MSG_INFO);
                        output.append(cmd).append(" : ");
                        executeCommand(session, cmd, output);
                        output.append("\n");
                    }
                    FileUtils.close(br);
                } catch (IOException e) {
                    if (getFailonerror()) {
                        throw new BuildException(e);
                    } else {
                        log("Caught exception: " + e.getMessage(),
                            Project.MSG_ERR);
                    }
                }
            }
        } catch (JSchException e) {
            if (getFailonerror()) {
                throw new BuildException(e);
            } else {
                log("Caught exception: " + e.getMessage(), Project.MSG_ERR);
            }
        } finally {
            if (outputProperty != null) {
                getProject().setNewProperty(outputProperty, output.toString());
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private void executeCommand(Session session, String cmd, StringBuffer sb)
        throws BuildException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TeeOutputStream tee =
            new TeeOutputStream(out,
                                KeepAliveOutputStream.wrapSystemOut());

        InputStream istream = null ;
        if (inputFile != null) {
            try {
                istream = new FileInputStream(inputFile) ;
            } catch (IOException e) {
                // because we checked the existence before, this one
                // shouldn't happen What if the file exists, but there
                // are no read permissions?
                log("Failed to read " + inputFile + " because of: "
                    + e.getMessage(), Project.MSG_WARN);
            }
        }
        if (inputProperty != null) {
            String inputData = getProject().getProperty(inputProperty) ;
            if (inputData != null) {
                istream = new ByteArrayInputStream(inputData.getBytes()) ;
            }        	
        }
        if (inputString != null) {
            istream = new ByteArrayInputStream(inputString.getBytes());
        }

        try {
            final ChannelExec channel;
            session.setTimeout((int) maxwait);
            /* execute the command */
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(cmd);
            channel.setOutputStream(tee);
            channel.setExtOutputStream(tee);
            if (istream != null) {
                channel.setInputStream(istream);
            }
            channel.setPty(usePty);
            channel.connect();
            // wait for it to finish
            thread =
                new Thread() {
                    public void run() {
                        while (!channel.isClosed()) {
                            if (thread == null) {
                                return;
                            }
                            try {
                                sleep(RETRY_INTERVAL);
                            } catch (Exception e) {
                                // ignored
                            }
                        }
                    }
                };

            thread.start();
            thread.join(maxwait);

            if (thread.isAlive()) {
                // ran out of time
                thread = null;
                if (getFailonerror()) {
                    throw new BuildException(TIMEOUT_MESSAGE);
                } else {
                    log(TIMEOUT_MESSAGE, Project.MSG_ERR);
                }
            } else {
                //success
                if (outputFile != null) {
                    writeToFile(out.toString(), append, outputFile);
                }

                // this is the wrong test if the remote OS is OpenVMS,
                // but there doesn't seem to be a way to detect it.
                int ec = channel.getExitStatus();
                if (ec != 0) {
                    String msg = "Remote command failed with exit status " + ec;
                    if (getFailonerror()) {
                        throw new BuildException(msg);
                    } else {
                        log(msg, Project.MSG_ERR);
                    }
                }
            }
        } catch (BuildException e) {
            throw e;
        } catch (JSchException e) {
            if (e.getMessage().indexOf("session is down") >= 0) {
                if (getFailonerror()) {
                    throw new BuildException(TIMEOUT_MESSAGE, e);
                } else {
                    log(TIMEOUT_MESSAGE, Project.MSG_ERR);
                }
            } else {
                if (getFailonerror()) {
                    throw new BuildException(e);
                } else {
                    log("Caught exception: " + e.getMessage(),
                        Project.MSG_ERR);
                }
            }
        } catch (Exception e) {
            if (getFailonerror()) {
                throw new BuildException(e);
            } else {
                log("Caught exception: " + e.getMessage(), Project.MSG_ERR);
            }
        } finally {
            sb.append(out.toString());
            FileUtils.close(istream);
        }
    }

    /**
     * Writes a string to a file. If destination file exists, it may be
     * overwritten depending on the "append" value.
     *
     * @param from           string to write
     * @param to             file to write to
     * @param append         if true, append to existing file, else overwrite
     * @exception Exception  most likely an IOException
     */
    private void writeToFile(String from, boolean append, File to)
        throws IOException {
        FileWriter out = null;
        try {
            out = new FileWriter(to.getAbsolutePath(), append);
            StringReader in = new StringReader(from);
            char[] buffer = new char[BUFFER_SIZE];
            int bytesRead;
            while (true) {
                bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
