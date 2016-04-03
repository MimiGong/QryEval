all:
ifeq ($(OS),Windows_NT)
	# assume windows
	javac -Xlint -cp ".;lucene-4.3.0/*" -g *.java
else
	# assume Linux
	javac -cp ".:lucene-4.3.0/*" -g *.java
endif

run: clean_result
	java -cp ".:lucene-4.3.0/*" QryEval ../parameters

clean_result:
	rm -f HW3-Train-0.teIn

clean:
	rm -f *.class
