/**
 * Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.util.*;

/**
 *  The SYN operator for all retrieval models.
 */
public class QryIopWindow extends QryIop {

    private int distance;

    public QryIopWindow(int n) {
        this.distance = n;
    }
    /**
     *  Evaluate the query operator; the result is an internal inverted
     *  list that may be accessed via the internal iterators.
     *  @throws IOException Error accessing the Lucene index.
     */
    protected void evaluate() throws IOException {

        //  Create an empty inverted list.  If there are no query arguments,
        //  that's the final result.

        this.invertedList = new InvList(this.getField());

        if (args.size() == 0) {
            return;
        }

        //  Each pass of the loop adds 1 document to result inverted list
        //  until all of the argument inverted lists are depleted.

        while (true) {

            //  Find the minimum next document id.  If there is none, we're done.

            int maxDocid = Integer.MIN_VALUE;

            for (Qry q_i : this.args) {
                if (q_i.docIteratorHasMatch(null)) {
                    int q_iDocid = q_i.docIteratorGetMatch();
                    if(q_iDocid == Qry.INVALID_DOCID) {
                        maxDocid = Qry.INVALID_DOCID;
                        break;
                    }
                    if (maxDocid < q_iDocid) {
                        maxDocid = q_iDocid;
                    }
                } else {
                    maxDocid = Qry.INVALID_DOCID;
                    break;
                }
            }

            if (maxDocid == Qry.INVALID_DOCID)
                break;                // All docids have been processed.  Done.

            // Advance all doc iterators to max doc id
            for (Qry q_i : this.args) {
                q_i.docIteratorAdvanceTo(maxDocid);
            }

            boolean allHasMatch = true;
            //check if all has match
            for (int i = 0; i < this.args.size(); i++) {
                QryIop q_i = this.getArg(i);
                if (!q_i.docIteratorHasMatch(null) ||
                        !(q_i.docIteratorGetMatch() == maxDocid)) {
                    allHasMatch = false;
                    break;
                }
            }



            //  Create a new posting that is the union of the posting lists
            //  that match the minDocid.  Save it.
            //  Note:  This implementation assumes that a location will not appear
            //  in two or more arguments.  #SYN (apple apple) would break it.

            // calculate only when all has match
            if(allHasMatch) {

                List<Integer> positions = new ArrayList<Integer>();
                boolean canContinue = true;
                while(canContinue) {
                    int minIndex = 0;
                    int min = Integer.MAX_VALUE;
                    int max = Integer.MIN_VALUE;
                    // get the max and min loc of the args
                    for (int i = 0; i < this.args.size(); i++) {
                        if(this.getArg(i).locIteratorHasMatch()) {
                            int loc = this.getArg(i).locIteratorGetMatch();
                            if(loc < min) {
                                minIndex = i;
                                min = loc;
                            }
                            if (loc > max) {
                                max = loc;
                            }
                        }
                        else {
                            canContinue = false;
                            break;
                        }
                    }
                    // if run out of positions, break
                    if(!canContinue)
                        break;
                    // check if positions are within n distance
                    boolean valid = (max - min) < this.distance;
                    if (valid) {
                        //add valid solution
                        positions.add(max);
                        // advance all pointers
                        for (int i = 0; i < this.args.size(); i++) {
                            this.getArg(i).locIteratorAdvance();
                        }
                    }
                    else {
                        // invalid, increase minIndex by one step
                        this.getArg(minIndex).locIteratorAdvance();
                    }
                }
                if(positions.size() > 0)
                    this.invertedList.appendPosting(maxDocid, positions);
                // process one doc, get next document
                for (int i = 0; i < this.args.size(); i++) {
                    this.getArg(i).docIteratorAdvancePast(maxDocid);
                }
            }
        }
    }

}