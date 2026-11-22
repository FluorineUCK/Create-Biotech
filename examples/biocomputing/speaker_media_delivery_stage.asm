; Coordinator to x0, speaker_media_output_stage to x1.
; Only a status job with poll=true can send another query while playback is pending.
TYPE Job RECORD o TEXT d ANY p BOOLEAN
TYPE Reply RECORD r ANY
REG job Job
REG response Reply
REG reply ANY
REG state ANY
REG code ANY
REG playback ANY
REG operation TEXT
REG remaining INTEGER
REG level INTEGER
REG polling BOOLEAN
REG flag BOOLEAN
next:
SLEEP poll_ticks
XBUS.STATUS level x0
LESS_INTEGER flag zero level
JFALSE flag next
XBUS.READ job x0
FIELD polling job p
JFALSE polling forward
FIELD operation job o
VALUE.EQUALS polling operation status_op
forward:
MOV remaining poll_limit
again:
XBUS.WRITE x1 job
XBUS.READ response x1
JFALSE polling return
FIELD reply response r
FIELD code reply code
VALUE.EQUALS flag code ok_code
JFALSE flag return
FIELD state reply state
FIELD playback state playback
VALUE.EQUALS flag playback preparing
JFALSE flag check_transfer
JMP wait
check_transfer:
VALUE.EQUALS flag playback transferring
JFALSE flag return
wait:
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER flag zero remaining
JFALSE flag timeout
SLEEP poll_ticks
JMP again
timeout:
CONST response {"r":{"code":"delivery_timeout"}}
return:
XBUS.WRITE x0 response
JMP next
