; Rows use device 0 = Library; 1 = Library with busy polling; 2 = Speaker status;
; 3 = Speaker play; 4 = Speaker delivery-status polling; 5 = Speaker stop.
; Mutations are sent once. A failed or ambiguous exchange leaves the read head for explicit recovery.
TYPE Position RECORD x INTEGER y INTEGER z INTEGER
TYPE Row RECORD o TEXT e TEXT p INTEGER a INTEGER
TYPE Rows LIST Row
TYPE Values LIST ANY
TYPE LibraryPacket RECORD kind TEXT target Position request TEXT operation TEXT sequence ANY data ANY
TYPE MediaPacket RECORD kind TEXT version INTEGER target Position request TEXT operation TEXT arguments ANY
TYPE PlayArgs RECORD device ANY session ANY revision ANY reference ANY
TYPE StopArgs RECORD device ANY session ANY revision ANY
TYPE Call RECORD p ANY e TEXT
TYPE Reply RECORD r ANY
REG rows Rows
REG stage TEXT
REG reply ANY
REG speaker_reply ANY
REG row Row
REG values Values
REG call Call
REG received Reply
REG lib LibraryPacket
REG media MediaPacket
REG play_args PlayArgs
REG stop_args StopArgs
REG args ANY
REG version INTEGER
REG sequence ANY
REG request TEXT
REG library_kind TEXT
REG library_reply TEXT
REG media_kind TEXT
REG media_reply TEXT
REG index INTEGER
REG count INTEGER
REG tries INTEGER
REG family INTEGER
REG arg INTEGER
REG expected TEXT
REG code ANY
REG reference ANY
REG state ANY
REG reading ANY
REG device ANY
REG session ANY
REG revision ANY
REG data ANY
REG is_library BOOLEAN
REG ok BOOLEAN
REG zero INTEGER
REG two INTEGER
REG three INTEGER
REG four INTEGER
REG five INTEGER
REG blank ANY
REG void ANY
REG busy TEXT
REG preparing TEXT
REG transferring TEXT
REG ref_op TEXT
CONST version 1
CONST zero 0
CONST two 2
CONST three 3
CONST four 4
CONST five 5
CONST library_kind "library_command"
CONST library_reply "library_reply"
CONST media_kind "speaker_media_command"
CONST media_reply "speaker_media_reply"
CONST busy "busy"
CONST preparing "preparing"
CONST transferring "transferring"
CONST ref_op "read_ref"
CONST blank {}
CONST void null
CONST sequence 0
CONST index 0
CONST stage "start"
CONST reply null
CONST speaker_reply null
CONST reference null
CONST device null
CONST session null
CONST revision null
MOV rows stop_rows
JFALSE play init
MOV rows play_rows
init:
LIST.MAKE values void track slot entry
LIST.LENGTH count rows
next:
LESS_INTEGER ok index count
JFALSE ok done
LIST.GET row rows index
FIELD stage row o
FIELD expected row e
FIELD family row p
FIELD arg row a
LESS_INTEGER is_library family two
MOV tries phase_polls
send:
CORRELATION request
JFALSE is_library speaker_frame
LIST.GET data values arg
RECORD.MAKE lib kind library_kind target library request request operation stage sequence sequence data data
RECORD.MAKE call p lib e library_reply
JMP exchange
speaker_frame:
MOV args blank
VALUE.EQUALS ok family three
JFALSE ok make_stop
RECORD.MAKE play_args device device session session revision revision reference reference
MOV args play_args
JMP finish_frame
make_stop:
VALUE.EQUALS ok family five
JFALSE ok finish_frame
RECORD.MAKE stop_args device device session session revision revision
MOV args stop_args
finish_frame:
RECORD.MAKE media kind media_kind version version target speaker request request operation stage arguments args
RECORD.MAKE call p media e media_reply
exchange:
XBUS.WRITE x0 call
XBUS.READ received x0
FIELD reply received r
FIELD code reply code
VALUE.EQUALS ok code expected
JFALSE ok rejected
JFALSE is_library speaker_state
FIELD sequence reply sequence
VALUE.EQUALS ok stage ref_op
JFALSE ok advance
FIELD reference reply value
JMP advance
speaker_state:
MOV speaker_reply reply
FIELD state reply state
FIELD device state device
FIELD session state session
FIELD revision state revision
VALUE.EQUALS ok family four
JFALSE ok advance
FIELD reading state playback
VALUE.EQUALS ok reading preparing
JFALSE ok check_transfer
JMP wait
check_transfer:
VALUE.EQUALS ok reading transferring
JFALSE ok advance
JMP wait
rejected:
JFALSE is_library done
VALUE.EQUALS ok family version
JFALSE ok done
VALUE.EQUALS ok code busy
JFALSE ok done
wait:
SUBTRACT_INTEGER tries tries version
LESS_INTEGER ok zero tries
JFALSE ok timeout
SLEEP phase_delay
JMP send
timeout:
CONST reply {"code":"phase_timeout"}
JMP done
advance:
ADD_INTEGER index index version
JMP next
done:
