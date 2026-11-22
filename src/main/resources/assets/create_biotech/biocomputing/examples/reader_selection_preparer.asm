TYPE MaybeText OPTIONAL TEXT
TYPE Material RECORD item MaybeText tag MaybeText count INTEGER components NBT
TYPE Tags LIST TEXT
TYPE Choice RECORD slot INTEGER item TEXT tags Tags
TYPE MaybeChoice OPTIONAL Choice
TYPE Choices LIST Choice
TYPE Request RECORD i MaybeText t MaybeText n INTEGER c NBT p MaybeChoice
TYPE Row RECORD m Material p Choice w INTEGER
TYPE Rows LIST Row
TYPE Panel RECORD r Rows e ANY
TYPE Exact RECORD item TEXT count INTEGER components NBT
TYPE MaybeExact OPTIONAL Exact
TYPE Reply RECORD c TEXT i MaybeExact
; One declared material, one real page. No implicit first-item choice or stock claim.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Range RECORD offset INTEGER count INTEGER total INTEGER next INTEGER
TYPE Components RECORD create_biotech:inventory_item_tags Choices
TYPE Page RECORD game_time INTEGER target Target components Components range Range
TYPE MaybePage OPTIONAL Page
TYPE Checked RECORD ok BOOLEAN value MaybePage
TYPE Outcome RECORD ok BOOLEAN value ANY
REG outcome Outcome
REG reply ANY
REG expected TEXT
REG code ANY
REG value ANY
REG checked Checked
REG optional MaybePage
REG page Page
REG components Components
REG choices Choices
REG choice Choice
REG row Row
REG rows Rows
REG panel Panel
REG ok BOOLEAN
REG more BOOLEAN
REG i INTEGER
REG one INTEGER
REG n INTEGER
REG startup INTEGER
CONST i 0
wait_for_emitter:
XBUS.STATUS startup x0
EQUAL_INTEGER ok startup i
JFALSE ok receive_page
SLEEP poll_ticks
JMP wait_for_emitter
receive_page:
XBUS.READ outcome x0
CONST expected "ok"
CONST one 1
LIST.MAKE rows
FIELD reply outcome value
FIELD ok outcome ok
JFALSE ok done
FIELD code reply code
VALUE.EQUALS ok code expected
JFALSE ok done
FIELD value reply value
VALUE.CHECK checked value
FIELD optional checked value
REQUIRE_VALUE page optional
FIELD components page components
FIELD choices components create_biotech:inventory_item_tags
LIST.LENGTH n choices
loop:
LESS_INTEGER more i n
JFALSE more done
LIST.GET choice choices i
RECORD.MAKE row m material p choice w poll_ticks
LIST.APPEND rows rows row
ADD_INTEGER i i one
JMP loop
done:
RECORD.MAKE panel r rows e reply
XBUS.WRITE x1 panel
