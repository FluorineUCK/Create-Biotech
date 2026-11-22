; Publish a read-only result using only the revision of this run's accepted click.
; Never ask for a new token or resubmit a failed publication.
TYPE Address RECORD x INTEGER y INTEGER z INTEGER
TYPE Packet RECORD kind TEXT version INTEGER target Address request TEXT operation TEXT arguments ANY
TYPE Call RECORD p Packet e TEXT
TYPE Reply RECORD r ANY
TYPE Publication RECORD state ANY layout ANY roots ANY
TYPE Rows LIST TEXT
TYPE Root RECORD output Rows
TYPE MaybeText OPTIONAL TEXT
TYPE CheckedText RECORD ok BOOLEAN value MaybeText
REG packet Packet
REG call Call
REG response Reply
REG publication Publication
REG rows Rows
REG a TEXT
REG b TEXT
REG c TEXT
REG d TEXT
REG e TEXT
REG f TEXT
REG g TEXT
REG h TEXT
REG i TEXT
REG j TEXT
REG label TEXT
REG roots Root
REG checked CheckedText
REG kind TEXT
REG expect_kind TEXT
REG operation TEXT
REG request TEXT
REG version INTEGER
REG state ANY
REG reply ANY
REG candidate ANY
REG maybe_text MaybeText
REG valid BOOLEAN
REG report_status TEXT

CONST report_status "report_not_confirmed"
FIELD state choice_reply state
; One compact line per result and one reusable text template keep the page
; inside a normal serial frame. All original result values remain in A.
CONST label "选择的工艺："
TEXT.CONCAT a label chosen_process
CONST label "停料与排空："
TEXT.CONCAT b label status
CONST label "排空检查点："
TEXT.CONCAT c label checkpoint
CONST label "过滤规则："
TEXT.CONCAT d label filter_result
CONST label "过滤检查点："
TEXT.CONCAT e label filter_checkpoint
CONST label "设备核对："
TEXT.CONCAT f label machine_result
CONST label "设备检查点："
TEXT.CONCAT g label machine_checkpoint
CONST label "复产信号："
TEXT.CONCAT h label resume_status
CONST label "复产检查点："
TEXT.CONCAT i label resume_checkpoint
CONST label "机械臂搬运："
TEXT.CONCAT j label route_status
LIST.MAKE rows a b c d e f g h i j
RECORD.MAKE roots output rows
RECORD.MAKE publication state state layout result_panel roots roots
CONST kind "terminal_ui_command"
CONST expect_kind "terminal_ui_reply"
CONST operation "publish"
CONST version 1
CORRELATION request
RECORD.MAKE packet kind kind version version target terminal_pos request request operation operation arguments publication
RECORD.MAKE call p packet e expect_kind
XBUS.WRITE x0 call
XBUS.READ response x0
FIELD reply response r
FIELD candidate reply code
VALUE.CHECK checked candidate
FIELD valid checked ok
JFALSE valid done
FIELD maybe_text checked value
REQUIRE_VALUE report_status maybe_text
done:
