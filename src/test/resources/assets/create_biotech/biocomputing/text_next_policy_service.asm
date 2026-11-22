; TEST ONLY: proposed ordinary canonical-policy worker, NOT a published role.
; x0 request {text:TEXT}; reply {ok:BOOLEAN}. poll_ticks is a public input.
; Policy (not TEXT.NEXT): UTF16 1..25, no ISO controls, not all Java whitespace,
; unchanged by Java trim (first/last codepoint > 0x20). Unicode non-blank edges allowed.
TYPE Request RECORD text TEXT
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
FIELD text request text
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
