; PUBLIC ROUTED F@1, Assembly v1: an ordinary editable, independent text-policy Computer.
; Wire E.x1 <-> F.x0; install and start F BEFORE E. poll_ticks is an explicit input.
; Request State={address:TEXT,device:TEXT,session:TEXT,revision:INTEGER}; reply={ok:BOOLEAN}.
; Only address is inspected. These editable copies are NOT authorization or current-host evidence.
; Visible policy: UTF16 length1..25, no ISO controls, not all Java whitespace,
; first/last codepoint>0x20 (unchanged by Java trim). Nonblank Unicode whitespace edges are allowed.
; TEXT.NEXT reads one codepoint. All policy iteration/branches below are ordinary paid ISA.
; Idle STATUS15/SLEEP tolerates E not started. No hidden retry/reset/timeout; pause explicitly afterward.
TYPE Request RECORD address TEXT device TEXT session TEXT revision INTEGER
TYPE Reply RECORD ok BOOLEAN
REG request Request
REG reply Reply
REG text TEXT
REG cursor INTEGER
REG point TEXT_NEXT
REG flag BOOLEAN
REG blank BOOLEAN
REG last INTEGER
REG cp INTEGER
REG trim_limit INTEGER
REG bound INTEGER
REG zero INTEGER
REG yes BOOLEAN
REG no BOOLEAN
REG waiting INTEGER
REG status INTEGER
REG result BOOLEAN
CONST yes true
CONST no false
CONST zero 0
CONST bound 25
CONST trim_limit 32
CONST waiting 15
await:
XBUS.STATUS status x0
EQUAL_INTEGER flag status waiting
JFALSE flag pause_receive
XBUS.READ request x0
FIELD text request address
MOV cursor zero
MOV blank yes
MOV last zero
scan:
TEXT.NEXT point text cursor
FIELD flag point done
JFALSE flag item
GREATER_INTEGER flag cursor zero
JFALSE flag reject
JFALSE blank not_blank
JMP reject
not_blank:
GREATER_INTEGER flag last trim_limit
JFALSE flag reject
MOV result yes
JMP respond
item:
FIELD cursor point next
GREATER_INTEGER flag cursor bound
JFALSE flag within
JMP reject
within:
FIELD flag point control
JFALSE flag no_control
JMP reject
no_control:
FIELD cp point codepoint
EQUAL_INTEGER flag last zero
JFALSE flag not_first
GREATER_INTEGER flag cp trim_limit
JFALSE flag reject
not_first:
MOV last cp
FIELD flag point whitespace
JFALSE flag nonblank
JMP scan
nonblank:
MOV blank no
JMP scan
reject:
MOV result no
respond:
RECORD.MAKE reply ok result
XBUS.WRITE x0 reply
JMP await
pause_receive:
SLEEP poll_ticks
JMP await
