; Token worker to x0, speaker_media_wire to x1. Format the frame and vouch only for an OK status.
TYPE Position RECORD x INTEGER y INTEGER z INTEGER
TYPE Intent RECORD o TEXT a ANY
TYPE Packet RECORD kind TEXT version INTEGER target Position request TEXT operation TEXT arguments ANY
TYPE Call RECORD p ANY e TEXT
TYPE Reply RECORD r ANY
TYPE Result RECORD wire Reply token ANY
REG intent Intent
REG packet Packet
REG call Call
REG response Reply
REG result Result
REG reply ANY
REG code ANY
REG operation TEXT
REG args ANY
REG token ANY
REG flag BOOLEAN
REG request TEXT
REG kind TEXT
REG expect TEXT
REG version INTEGER
REG level INTEGER
CONST kind "speaker_media_command"
CONST expect "speaker_media_reply"
CONST version 1
next:
SLEEP poll_ticks
XBUS.STATUS level x0
LESS_INTEGER flag zero level
JFALSE flag next
XBUS.READ intent x0
FIELD operation intent o
FIELD args intent a
CORRELATION request
RECORD.MAKE packet kind kind version version target speaker request request operation operation arguments args
RECORD.MAKE call p packet e expect
XBUS.WRITE x1 call
XBUS.READ response x1
CONST token null
VALUE.EQUALS flag operation status_op
JFALSE flag return
FIELD reply response r
FIELD code reply code
VALUE.EQUALS flag code ok_code
JFALSE flag return
FIELD token reply state
return:
RECORD.MAKE result wire response token token
XBUS.WRITE x0 result
JMP next
