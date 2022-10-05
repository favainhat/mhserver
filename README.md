mhserver
=====
monster hunter(Playstation 2) server based on https://gitlab.com/gh0stl1ne/Bioserver1

There are still many bugs to fix.

Use this for MH2
```
//Https cert patch
patch=1,EE,D090940C,extended,0000000b
patch=1,EE,2090940C,extended,0c24250f
```


You may need to adjust area.bin to enter lobby for G or 2<br>
I made note for it. See the source. (Of course there are areaG.bin and areaDos.bin.)<br><br>

## Notable bugs:

- No matter how many people you specify in quest, it will be 4 people.
- Monster size is might always the same. -> 0x6882 or unknown
- The percentage of the Old Dragon Observatory is always 0%. So Elder Dragon Intercept is might not supported. -> 0x6884
- Because session is destroyed if you go quest, or reconnection, You are returned to main online menu instead of return to city. 
- Monster price list are empty. (Monster paid price is also zero...) -> 0x6884
- Time change is not accurate, and There is no Festa.
- The recruitment text is blank,  I couldn't make room message is set by user. so please use the room that has already created.
- area description text cropped.
- Online mark in MH2 is not yet supported and also friend search.
- PVP Arena is not expected to work.