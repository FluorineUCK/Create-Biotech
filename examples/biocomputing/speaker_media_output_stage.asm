; Coordinator to x0, speaker_media_frame_stage to x1.
; Use each successful status token at most once, even if the action reply is lost.
; The status opcode is an initial input (accounted as RAM), not a server-side shortcut.
; Job.d is the unmodified Library reference for play, and null for status/stop.
TYPE Job RECORD o TEXT d ANY p BOOLEAN
TYPE Intent RECORD o TEXT a ANY
TYPE Reply RECORD r ANY
TYPE Result RECORD wire Reply token ANY
TYPE PlayArgs RECORD device ANY session ANY revision ANY reference ANY
TYPE StopArgs RECORD device ANY session ANY revision ANY
REG job Job
REG intent Intent
REG response Reply
REG result Result
REG token ANY
REG reference ANY
REG play_args PlayArgs
REG stop_args StopArgs
REG args ANY
REG device ANY
REG session ANY
REG revision ANY
REG operation TEXT
REG level INTEGER
REG flag BOOLEAN
CONST token null
next:
SLEEP poll_ticks
XBUS.STATUS level x0
LESS_INTEGER flag zero level
JFALSE flag next
XBUS.READ job x0
FIELD operation job o
VALUE.EQUALS flag operation status_op
JFALSE flag mutate
CONST args {}
JMP send
mutate:
IS_NULL flag token
JFALSE flag build_args
CONST response {"r":{"code":"status_required"}}
JMP return
build_args:
FIELD device token device
FIELD session token session
FIELD revision token revision
FIELD reference job d
IS_NULL flag reference
JFALSE flag with_reference
RECORD.MAKE stop_args device device session session revision revision
MOV args stop_args
JMP send
with_reference:
RECORD.MAKE play_args device device session session revision revision reference reference
MOV args play_args
send:
RECORD.MAKE intent o operation a args
CONST token null
XBUS.WRITE x1 intent
XBUS.READ result x1
FIELD token result token
FIELD response result wire
return:
XBUS.WRITE x0 response
JMP next
