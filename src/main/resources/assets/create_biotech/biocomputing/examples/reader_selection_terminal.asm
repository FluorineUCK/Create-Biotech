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
; Called by reader_select@2 after its visible Flow transaction acquires choice_in_flight.
; Adapt the original material and chosen descriptor only after explicit selection.
; The wrapper releases the cell only after this procedure receives S's reply. No order or retry.
REG item MaybeText
REG tag MaybeText
REG count INTEGER
REG patch NBT
REG request Request
REG reply Reply
REG fifteen INTEGER
REG status INTEGER
REG ready BOOLEAN
FIELD item m item
FIELD tag m tag
FIELD count m count
FIELD patch m components
RECORD.MAKE request i item t tag n count c patch p p
XBUS.WRITE x1 request
CONST fifteen 15
wait:
XBUS.STATUS status x1
EQUAL_INTEGER ready status fifteen
JFALSE ready pause
XBUS.READ reply x1
RET
pause:
SLEEP w
JMP wait
