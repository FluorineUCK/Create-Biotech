; Coordinator connects to x0; the existing speaker_media_wire connects to x1.
; Repeat only result/read_ref when marked for polling and the Library reports busy.
TYPE Position RECORD x INTEGER y INTEGER z INTEGER
TYPE Job RECORD o TEXT d ANY p BOOLEAN
TYPE Packet RECORD kind TEXT target Position request TEXT operation TEXT sequence ANY data ANY
TYPE Call RECORD p ANY e TEXT
TYPE Reply RECORD r ANY
REG job Job
REG packet Packet
REG call Call
REG response Reply
REG reply ANY
REG data ANY
REG operation TEXT
REG request TEXT
REG sequence ANY
REG sequence_field TEXT
REG flag BOOLEAN
REG polling BOOLEAN
REG remaining INTEGER
REG level INTEGER
REG code ANY
REG kind TEXT
REG expect TEXT
CONST sequence 0
CONST sequence_field "sequence"
CONST kind "library_command"
CONST expect "library_reply"
next:
SLEEP poll_ticks
XBUS.STATUS level x0
LESS_INTEGER flag zero level
JFALSE flag next
XBUS.READ job x0
FIELD operation job o
FIELD data job d
FIELD polling job p
JFALSE polling send
VALUE.EQUALS polling operation result_op
VALUE.EQUALS flag operation reference_op
OR_BOOLEAN polling polling flag
send:
MOV remaining poll_limit
again:
CORRELATION request
RECORD.MAKE packet kind kind target library request request operation operation sequence sequence data data
RECORD.MAKE call p packet e expect
XBUS.WRITE x1 call
XBUS.READ response x1
FIELD reply response r
HAS.FIELD flag reply sequence_field
JFALSE flag consider_polling
FIELD sequence reply sequence
consider_polling:
JFALSE polling return
FIELD code reply code
VALUE.EQUALS flag code busy_code
JFALSE flag return
SUBTRACT_INTEGER remaining remaining one
LESS_INTEGER flag zero remaining
JFALSE flag return
SLEEP poll_ticks
JMP again
return:
XBUS.WRITE x0 response
JMP next
