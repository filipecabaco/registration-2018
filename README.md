# Worker Registration

**NOTE: DO NOT USE OFICIAL DOCUMENT, PLEASE CREATE A MOCKUP TO AVOID THE USAGE OF PRIVATE DATA**

Simple application that uses Holiday XLS to create the Worker Registration Excel Sheet

## How to use?
### Generate Jar

1. `brew install leiningen`
1. `lein uberjar`

### Server Mode

`PORT=3000 java -jar registration.jar server`

`curl -X POST -F "vacation=@./2018.xlsx" -F "signature=@./Filipe Cabaço.png" "localhost:3000/?month=Jan&year=2018&name=Filipe%20Caba%C3%A7o" > Filipe.xlsx`

### CLI Mode

`java -jar registration.jar cli Feb 2018 "Filipe Cabaço" ~/Desktop/2018.xlsx ~/Desktop/Filipe\ Cabaço.png output.xlsx`

## Arguments

1. `mode` - If you want to run CLI or Server mode
1. `vacation` - the vacation XLSX file
1. `signature` - PNG picture of your signature
1. `month` - Month for the registration sheet you want
1. `year` - Year for the registration sheet you want
1. `name` - Employee name
1. `filename` - Filename for the saved XLSX file

## How to automate?
This is how you setup the script to run every Friday at 15h00 and it sync it directly to Google Drive:

**NOTE: We need a shared folder to put all files of a month, TBD later**

1. Download Backup and Sync from `https://www.google.com/drive/download/`
1. Set to sync the `~/Documents` folder
1. Copy `registration.jar` into `~/Documents/`
1. Run `chmod 755 registration.sh`
1. Run `chmod 755 registration.jar`
1. Add your signature `PNG` file into `~/Documents/`
1. `env EDITOR=vim crontab -e`
1. Add the following line: `0 15 * * 5 ~/Documents/registration.sh >> ~/Documents/registration.log 2>&1`