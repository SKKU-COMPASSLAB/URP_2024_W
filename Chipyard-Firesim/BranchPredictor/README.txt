code.zip
이번 URP 동안 설계한 코드들입니다. 
rocket core의 BTB.scala 파일에 설계를 진행하였습니다.
이 코드들의 원래 이름은 BTB.scala이지만, 편의를 위해 이름을 변경하였습니다.
6주 동안 감사했습니다!

BTB.zip
설계를 진행한 BTB.scala의 경로는 /chipyard/generators/rocket-chip/src/main/scala/rocket/BTB.scala입니다.
하지만 지금 보내드린 BTB.scala의 경우, /chipyard/generators/shuttle/src/main/scala/ifu/BTB.scala 경로의 파일로, 설계를 진행한 파일과는 다른 파일입니다.
TAGE predictor의 경우 이 파일도 수정을 해야 했어서 보내드립니다.


BTB.zip은 설계를 한 것은 아니고,
원래는 첫번째 경로에 있는 BTB.scala에만 코드를 설계했습니다. 첫번째 경로의 BTB.scala 안의 함수를 변형하면서 함수가 입력받는 변수를 바꿨는데, 시뮬레이션을 하려고 컴파일을 하니 저 두번째 BTB.scala에서도 그 함수에 대해 입력 변수를 바꿔주어야 했습니다. 그래서 두번째 BTB.scala에선  코드가 거의 변화하진 않고 함수의 입력 변수만 바꾸어 주었습니다.