# espcon
`espcon` is a terminal only version of ESPlorer.

For now it supports only esp8266 with lua and is actively used under linux.

## options
```
usage: espcom
 -b,--baud <BAUD>   serial port baud rate, default = 230400
 -d,--debug         print debugging information
 -h,--help          print help message and exit
 -l,--list          list available serial ports
 -p,--port <arg>    serial port device
 -v,--verbose       be extra verbose
```
## quick start

### connect you esp module and find out device name
```
$ java -jar espcon.jar -l
available serial ports:
/dev/ttyUSB0
```
### launch
```
$ java -jar espcon.jar -p /dev/ttyUSB0
```

### usage
Once started and connected to serial port with esp, you need to press ENTER to sync the communication.
As soon as communication is established you will see a lua prompt `>`.
Complete lines are send to lua.
```
About to open port /dev/ttyUSB0, baud 230400, 8N1                                                                                 
press ENTER to sync ...                                                                                                           
>                                                                                                                                 
>                                                                                                                                 
> =node.flashid()                                                                                                                 
1458376                                                                                                                           
> =node.heap()                                                                                                                    
45240                                                                                                                             
> =2+2                                                                                                                            
4                                                                                                                                 
>
```
You can use the usual readline keybindings. E.g. Up brings the previous command, `^R` activates reverse search.
End of file character `^D` terminates the session.

`espcon` intercepts any commands starting with lua single line comment `--`. Current list of commands:
```
--quit - terminate session, same as ^D
--ls - list esp files
--cat filename - show content of a file from esp
--hexdump filename - hexdump a file from esp
--upload src [target] - upload any file
--save src [target] - upload and optionally run file
--tsave src [target] - upload and optionally run file (turbo mode)
--set autorun - run content of a file after (t)save
--reset autorun - don't run content of a file after (t)save
--echo on|off - show commands send to esp
```

### TODO

* status line
* autocomplete


### credits
The code handling esp communication is inspired by ESPlorer.

Terminal handling and line editing borrows heavily from https://github.com/jline/jline3.

