; A.x0 to the existing public serial_exchange x0. One note per explicit run.
; play=false only queries. play=true obtains current state then submits exactly one note.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Target request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE NoteArgs RECORD device ANY session ANY revision ANY instrument TEXT note INTEGER
REG packet Packet
REG call Call
REG received Reply
REG action NoteArgs
REG args ANY
REG state ANY
REG device ANY
REG session ANY
REG revision ANY
REG kind TEXT
REG expected TEXT
REG op TEXT
REG request TEXT
REG field TEXT
REG version INTEGER
REG ok BOOLEAN
REG reply ANY
CONST kind "music_output_command"
CONST expected "music_output_reply"
CONST version 1
CONST op "status"
CONST args {}
CONST field "state"
send:
CORRELATION request
RECORD.MAKE packet kind kind version version target target request request operation op arguments args
RECORD.MAKE call p packet e expected
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
JFALSE play done
HAS.FIELD ok reply field
JFALSE ok done
FIELD state reply state
FIELD device state device
FIELD session state session
FIELD revision state revision
RECORD.MAKE action device device session session revision revision instrument instrument note note
MOV args action
; Clear the invocation-local play flag before the single send.
CONST play false
CONST op "play"
JMP send
done:
